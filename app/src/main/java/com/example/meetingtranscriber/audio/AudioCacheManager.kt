package com.example.meetingtranscriber.audio

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.example.meetingtranscriber.MeetingApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 播放/分享用音频缓存
 *
 * 录音文件有两种格式（见 WavRecorder）：
 * - "RIFF"：标准明文 WAV，可直接播放/下载，原样返回
 * - "MTEW"：EncryptedFile 加密（20 字节头 + PCM 16k/16bit/mono），外部无法读取，
 *   解密转成标准 WAV 缓存到 cacheDir/decrypted_audio/
 *
 * 权衡说明：解密后的明文 WAV 落在 app 私有 cache 目录——App 启动时整目录清空，
 * 系统 cache 压力回收兜底，Android 10+ FBE 全盘加密保底。这是"录音可播放、
 * 可扫码下载"功能的必要代价。
 */
object AudioCacheManager {

    private const val TAG = "AudioCacheManager"
    private const val CACHE_DIR = "decrypted_audio"
    private const val SAMPLE_RATE = 16000
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1
    /** 截断流部分恢复的最低有效音频量（1 秒），低于此丢弃 */
    private const val MIN_SALVAGE_BYTES = SAMPLE_RATE * 2L

    /**
     * 获取可播放/可下载的标准 WAV 文件。
     *
     * - RIFF 明文 → 原样返回源文件
     * - MTEW 加密 → 解密转标准 WAV（缓存复用，同一源文件只转一次）
     * - 文件缺失/解密失败 → null
     */
    suspend fun getPlayableWav(context: Context, audioFilePath: String): File? =
        withContext(Dispatchers.IO) {
            val source = File(audioFilePath)
            if (!source.exists() || source.length() == 0L) return@withContext null

            try {
                // 检测文件格式（同 WavPlayer 的分派逻辑）
                val first4 = ByteArray(4)
                FileInputStream(source).use { it.read(first4) }
                if (first4.decodeToString() == "RIFF") {
                    return@withContext source
                }

                // 缓存键含源文件大小，源文件变化即失效
                val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
                val cacheName = "${source.nameWithoutExtension}_${source.length()}.wav"
                val cached = File(cacheDir, cacheName)
                if (cached.exists()) return@withContext cached

                decryptToWav(source, cached)
            } catch (e: Exception) {
                Log.e(TAG, "音频转换失败: ${e.message}", e)
                null
            }
        }

    /** MTEW 解密流 → 标准 44 字节头 WAV。先写 .tmp 再 rename，exists 即完整 */
    private fun decryptToWav(source: File, target: File): File? {
        val masterKey = MasterKey.Builder(MeetingApplication.instance)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encryptedFile = EncryptedFile.Builder(
            MeetingApplication.instance,
            source,
            masterKey,
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
                    raf.write(buildWavHeader(0))  // 占位头
                    val buffer = ByteArray(8 * 1024)
                    try {
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            total += read
                        }
                    } catch (e: Exception) {
                        // 崩溃/进程被杀时加密流未收尾，读到截断处会抛异常：
                        // 前面的分段均已通过认证，保留已解出的部分而非整个文件判废
                        Log.w(TAG, "加密流尾部不完整，保留前 ${total / 32}ms: ${e.message}")
                    }
                    raf.seek(0)
                    raf.write(buildWavHeader(total.toInt()))  // 回填真实 dataSize
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

    /** 清空解密缓存目录（App 启动时调用） */
    fun cleanup(context: Context) {
        try {
            File(context.cacheDir, CACHE_DIR).deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "清理解密缓存失败: ${e.message}")
        }
    }

    // ── 标准 WAV 头（16k/16bit/mono，与 WavRecorder.writeWavHeader 一致） ──

    private fun buildWavHeader(dataSize: Int): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val buf = ByteBuffer.allocate(44).apply { order(ByteOrder.LITTLE_ENDIAN) }

        buf.put('R'.code.toByte())
        buf.put('I'.code.toByte())
        buf.put('F'.code.toByte())
        buf.put('F'.code.toByte())
        buf.putInt(36 + dataSize)

        buf.put('W'.code.toByte())
        buf.put('A'.code.toByte())
        buf.put('V'.code.toByte())
        buf.put('E'.code.toByte())

        buf.put('f'.code.toByte())
        buf.put('m'.code.toByte())
        buf.put('t'.code.toByte())
        buf.put(' '.code.toByte())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(CHANNELS.toShort())
        buf.putInt(SAMPLE_RATE)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(BITS_PER_SAMPLE.toShort())

        buf.put('d'.code.toByte())
        buf.put('a'.code.toByte())
        buf.put('t'.code.toByte())
        buf.put('a'.code.toByte())
        buf.putInt(dataSize)

        return buf.array()
    }
}
