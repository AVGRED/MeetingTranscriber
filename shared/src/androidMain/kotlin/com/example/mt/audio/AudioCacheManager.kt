package com.example.mt.audio

import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.example.mt.platform.getAppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * Android actual：播放/分享用音频缓存。
 *
 * 录音文件有两种格式（见 WavRecorder）：
 * - "RIFF"：标准明文 WAV，可直接播放/下载，原样返回
 * - "MTEW"：EncryptedFile 加密（20 字节头 + PCM 16k/16bit/mono），外部无法读取，
 *   解密转成标准 WAV 缓存到 cacheDir/decrypted_audio/
 *
 * 权衡说明：解密后的明文 WAV 落在 app 私有 cache 目录——启动时按额度修剪
 * （≤300MB / 7 天，超额按 LRU 淘汰），系统 cache 压力回收兜底，Android 10+
 * FBE 全盘加密保底。这是"录音可播放、可扫码下载"功能的必要代价。
 */
actual object AudioCacheManager {

    private const val TAG = "AudioCacheManager"
    private const val CACHE_DIR = "decrypted_audio"
    private const val SAMPLE_RATE = 16000
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1
    /** 截断流部分恢复的最低有效音频量（1 秒），低于此丢弃 */
    private const val MIN_SALVAGE_BYTES = SAMPLE_RATE * 2L
    /** 解密缓存保留上限：约 2.6 小时录音；超额按 lastModified 从旧到新淘汰 */
    private const val MAX_CACHE_BYTES = 300L * 1024 * 1024
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    actual suspend fun getPlayableWav(audioFilePath: String): String? =
        withContext(Dispatchers.IO) {
            val source = File(audioFilePath)
            if (!source.exists() || source.length() == 0L) return@withContext null

            try {
                // 检测文件格式（同 WavPlayer 的分派逻辑）
                val first4 = ByteArray(4)
                FileInputStream(source).use { fis ->
                    if (fis.read(first4) < 4) {
                        Log.e(TAG, "文件头不完整: ${source.absolutePath}")
                        return@withContext null
                    }
                }
                if (first4.decodeToString() == "RIFF") {
                    return@withContext audioFilePath
                }

                val ctx = getAppContext()
                    ?: return@withContext null

                // 缓存键含完整路径 hash + 文件大小，避免同名不同目录碰撞、源文件变化即失效
                val cacheDir = File(ctx.cacheDir, CACHE_DIR).apply { mkdirs() }
                val pathHash = source.absolutePath.hashCode().toUInt().toString(16)
                val cacheName = "${source.nameWithoutExtension}_${pathHash}_${source.length()}.wav"
                val cached = File(cacheDir, cacheName)
                if (cached.exists()) {
                    cached.setLastModified(System.currentTimeMillis())  // LRU 记账
                    return@withContext cached.absolutePath
                }

                decryptToWav(source, cached, ctx.cacheDir.absolutePath)?.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "音频转换失败: ${e.message}", e)
                null
            }
        }

    /** MTEW 解密流 → 标准 44 字节头 WAV。先写 .tmp 再 rename，exists 即完整 */
    private fun decryptToWav(source: File, target: File, _cacheDir: String): File? {
        val ctx = getAppContext() ?: return null
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encryptedFile = EncryptedFile.Builder(
            ctx, source, masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            var total = 0L
            encryptedFile.openFileInput().use { input ->
                val header = ByteArray(20)
                if (input.read(header) < 20) {
                    Log.e(TAG, "MTEW 文件头不完整")
                    return null
                }
                val magic = header.copyOfRange(0, 4).decodeToString()
                if (magic != "MTEW") {
                    Log.w(TAG, "MTEW 魔数不匹配: $magic，仍按 PCM 流处理")
                }

                RandomAccessFile(tmp, "rw").use { raf ->
                    raf.write(WavHeaderBuilder.buildHeader(0))  // 占位头
                    val buffer = ByteArray(8 * 1024)
                    try {
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            total += read
                        }
                    } catch (e: Exception) {
                        if (total == 0L) {
                            // 没有任何数据写入 → 磁盘满/首帧解密失败 → 不可恢复
                            Log.e(TAG, "解密流写入失败（磁盘满/文件损坏？）: ${e.message}")
                            throw e  // 传播到外层 catch → 删除 .tmp → 返回 null
                        }
                        // 已有数据 → input.read() 遇到截断流（崩溃/进程被杀时 GCM 未收尾）
                        // 前面的分段均已通过认证，保留已解出的部分而非整个文件判废
                        Log.w(TAG, "加密流尾部不完整，保留前 ${total / 32}ms: ${e.message}")
                    }
                    raf.seek(0)
                    raf.write(WavHeaderBuilder.buildHeader(total.toInt()))  // 回填真实 dataSize
                }
            }
            if (total < MIN_SALVAGE_BYTES) {
                Log.e(TAG, "有效音频不足 1s（${total}B），放弃")
                tmp.delete()
                return null
            }
            return if (tmp.renameTo(target)) target else null
        } catch (e: Exception) {
            Log.e(TAG, "MTEW 解密失败: ${e.message}", e)
            tmp.delete()
            return null
        }
    }

    actual fun trim() {
        try {
            val ctx = getAppContext() ?: return
            val dir = File(ctx.cacheDir, CACHE_DIR)
            val files = dir.listFiles() ?: return
            val now = System.currentTimeMillis()
            val kept = files.filter { f ->
                val drop = !f.isFile || f.name.endsWith(".tmp") ||
                    now - f.lastModified() > MAX_AGE_MS
                if (drop) f.deleteRecursively()
                !drop
            }
            var totalBytes = kept.sumOf { it.length() }
            if (totalBytes > MAX_CACHE_BYTES) {
                for (f in kept.sortedBy { it.lastModified() }) {
                    if (totalBytes <= MAX_CACHE_BYTES) break
                    totalBytes -= f.length()
                    f.delete()
                }
            }
            Log.i(TAG, "解密缓存修剪完成，保留 ${totalBytes / (1024 * 1024)}MB")
        } catch (e: Exception) {
            Log.w(TAG, "修剪解密缓存失败: ${e.message}")
        }
    }
}
