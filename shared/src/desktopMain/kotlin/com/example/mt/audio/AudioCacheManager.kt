package com.example.mt.audio

import com.example.mt.platform.FileAccess
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Desktop actual：播放/分享用音频缓存。
 *
 * Desktop 端不加密，录音均为标准 RIFF WAV。此实现：
 * - RIFF 明文 → 原样返回
 * - 其他格式 → null（Desktop 不处理加密文件）
 * - trim() → 清理 cacheDir/decrypted_audio/ 超龄文件（保留 7 天）
 */
actual object AudioCacheManager {

    private const val CACHE_DIR = "decrypted_audio"
    /** 缓存保留上限：约 2.6 小时录音 */
    private const val MAX_CACHE_BYTES = 300L * 1024 * 1024
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    actual suspend fun getPlayableWav(audioFilePath: String): String? =
        withContext(Dispatchers.IO) {
            val source = File(audioFilePath)
            if (!source.exists() || source.length() == 0L) return@withContext null

            try {
                // 检测文件格式
                val first4 = ByteArray(4)
                FileInputStream(source).use { fis ->
                    if (fis.read(first4) < 4) {
                        Napier.e("AudioCacheManager: 文件头不完整: ${source.absolutePath}")
                        return@withContext null
                    }
                }
                if (first4.decodeToString() == "RIFF") {
                    audioFilePath  // 明文 WAV，原样返回
                } else {
                    // Desktop 不加密，遇到非 RIFF 文件返回 null
                    Napier.w("AudioCacheManager: 不支持的格式 ${first4.decodeToString()}（Desktop 仅支持 RIFF）")
                    null
                }
            } catch (e: Exception) {
                Napier.e("AudioCacheManager: 音频读取失败", e)
                null
            }
        }

    actual fun trim() {
        try {
            val appDir = File(FileAccess().getDataDir()).parentFile ?: return
            val dir = File(appDir, CACHE_DIR)
            if (!dir.exists()) return

            val files = dir.listFiles() ?: return
            val now = System.currentTimeMillis()
            val kept = files.filter { f ->
                val drop = !f.isFile || f.name.endsWith(".tmp") ||
                    now - f.lastModified() > MAX_AGE_MS
                if (drop) {
                    if (!f.deleteRecursively()) Napier.w("AudioCacheManager: 无法删除缓存文件 ${f.absolutePath}")
                }
                !drop
            }
            var totalBytes = kept.sumOf { it.length() }
            if (totalBytes > MAX_CACHE_BYTES) {
                for (f in kept.sortedBy { it.lastModified() }) {
                    if (totalBytes <= MAX_CACHE_BYTES) break
                    val size = f.length()
                    if (f.delete()) {
                        totalBytes -= size
                    } else {
                        Napier.w("AudioCacheManager: 无法删除缓存文件 ${f.absolutePath}")
                    }
                }
            }
        } catch (e: Exception) {
            Napier.e("AudioCacheManager: 修剪缓存失败", e)
        }
    }
}
