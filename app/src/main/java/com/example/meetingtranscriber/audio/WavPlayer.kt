package com.example.meetingtranscriber.audio

import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.example.meetingtranscriber.MeetingApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream
import javax.crypto.SecretKey

/**
 * 音频文件回放器
 *
 * 自动检测文件格式并选择合适的解码方式：
 * - "RIFF" → 标准 WAV（明文，跳过 44 字节头）
 * - "MTEW" → 加密 MTEW（通过 EncryptedFile 解密，跳过 20 字节头）
 * - 其他  → 尝试 EncryptedFile 解密（兼容旧版加密文件）
 */
object WavPlayer {

    private const val TAG = "WavPlayer"
    private const val CHUNK_MS = 100L
    private const val CHUNK_SIZE = 3200  // 16000 * 2 * 100 / 1000

    fun play(file: File, encryptionKey: SecretKey? = null): Flow<ByteArray> = flow {
        if (!file.exists()) {
            Log.e(TAG, "文件不存在: ${file.absolutePath}")
            return@flow
        }

        // 检测文件格式
        val first4 = ByteArray(4)
        FileInputStream(file).use { it.read(first4) }
        val magic = first4.decodeToString()

        when {
            magic == "RIFF" -> playWav(file)
            magic == "MTEW" || encryptionKey != null -> playEncryptedMTEW(file)
            else -> {
                // 未知格式，尝试作为明文 PCM 或加密文件
                Log.w(TAG, "未知文件格式 $magic，尝试解密读取")
                playEncryptedMTEW(file)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<ByteArray>.playWav(file: File) {
        val fis = FileInputStream(file)
        try {
            val header = ByteArray(44)
            if (fis.read(header) < 44) {
                Log.e(TAG, "WAV 文件头不完整")
                return
            }
            emitPCMFrames(fis)
        } finally {
            try { fis.close() } catch (_: Exception) {}
        }
    }

    private suspend fun FlowCollector<ByteArray>.playEncryptedMTEW(file: File) {
        var stream: java.io.InputStream? = null
        try {
            val masterKey = MasterKey.Builder(MeetingApplication.instance)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encryptedFile = EncryptedFile.Builder(
                MeetingApplication.instance,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            stream = encryptedFile.openFileInput()
            val header = ByteArray(20)
            val headerRead = stream.read(header)
            if (headerRead < 20) {
                Log.e(TAG, "MTEW 文件头不完整")
                return
            }
            val headMagic = header.copyOfRange(0, 4).decodeToString()
            if (headMagic != "MTEW") {
                Log.w(TAG, "MTEW 文件头魔数不匹配: $headMagic")
            }
            emitPCMFrames(stream)
        } catch (e: Exception) {
            Log.e(TAG, "加密文件读取失败: ${e.message}", e)
        } finally {
            try { stream?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun FlowCollector<ByteArray>.emitPCMFrames(input: java.io.InputStream) {
        val buffer = ByteArray(CHUNK_SIZE)
        var lastEmitTime = System.currentTimeMillis()

        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break

            val frame = if (read == CHUNK_SIZE) {
                buffer.copyOf()
            } else {
                ByteArray(CHUNK_SIZE).also { System.arraycopy(buffer, 0, it, 0, read) }
            }

            emit(frame)

            val elapsed = System.currentTimeMillis() - lastEmitTime
            val waitMs = CHUNK_MS - elapsed
            if (waitMs > 0) delay(waitMs)
            lastEmitTime = System.currentTimeMillis()
        }
    }
}
