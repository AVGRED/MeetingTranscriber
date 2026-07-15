package com.example.meetingtranscriber.audio

import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.example.meetingtranscriber.MeetingApplication
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.SecretKey

/**
 * 离线 WAV 录音器
 *
 * 支持两种模式：
 * - 明文模式（key=null）：标准 WAV 格式（44 字节 RIFF 头 + PCM，支持 seek 回写 dataSize）
 * - 加密模式（key!=null）：通过 EncryptedFile 写入，MTEW 格式（20 字节头 + PCM），无需 seek
 */
class WavRecorder(
    private val outputFile: File,
    private val encryptionKey: SecretKey? = null
) {

    companion object {
        private const val TAG = "WavRecorder"
        private const val SAMPLE_RATE = 16000
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNELS = 1

        // MTEW magic bytes
        private val MTEW_MAGIC = byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), 'W'.code.toByte())
    }

    private var raf: RandomAccessFile? = null
    private var plainOutputStream: FileOutputStream? = null
    private var encryptedFile: EncryptedFile? = null
    private var encryptedOutputStream: FileOutputStream? = null
    /** 加密流外包 64KB 缓冲：EncryptedFile 每次 write 都走 Tink AES 段加密 + syscall，
     *  10 次/s 逐帧写在低端机是持续 IO 抖动源；批量化后降到 ~0.5 次/s。
     *  代价：崩溃最多丢 2s 尾音（恢复机制本就是 5s 粒度，影响可忽略） */
    private var bufferedOut: java.io.BufferedOutputStream? = null
    private var totalBytes = 0L

    @Volatile var isRecording = false
        private set

    /** 返回当前是否使用加密 */
    val isEncrypted: Boolean get() = encryptionKey != null

    fun start(): Boolean {
        if (isRecording) return true
        return try {
            outputFile.parentFile?.mkdirs()

            if (encryptionKey != null) {
                startEncrypted()
            } else {
                startPlain()
            }
            isRecording = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "创建录音文件失败: ${e.message}")
            if (encryptionKey != null) {
                Log.w(TAG, "加密录音失败，尝试明文模式")
                try { startPlain(); isRecording = true; true } catch (e2: Exception) { false }
            } else {
                false
            }
        }
    }

    private fun startEncrypted() {
        val masterKey = MasterKey.Builder(MeetingApplication.instance)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        encryptedFile = EncryptedFile.Builder(
            MeetingApplication.instance,
            outputFile,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        encryptedOutputStream = encryptedFile!!.openFileOutput() as FileOutputStream
        bufferedOut = java.io.BufferedOutputStream(encryptedOutputStream, 64 * 1024)

        // 写入 MTEW 头（20 字节）作为加密流的开头
        val header = buildMTEWHeader()
        bufferedOut!!.write(header)
        bufferedOut!!.flush()
    }

    private fun startPlain() {
        raf = RandomAccessFile(outputFile, "rw")
        writeWavHeader(raf!!, 0)
    }

    fun write(pcmData: ByteArray) {
        try {
            if (bufferedOut != null) {
                bufferedOut!!.write(pcmData)
            } else {
                raf?.write(pcmData)
            }
            totalBytes += pcmData.size
        } catch (e: Exception) {
            Log.w(TAG, "写入音频数据失败: ${e.message}")
        }
    }

    /** 停止录音，返回总字节数 */
    fun stop(): Long {
        isRecording = false
        return try {
            if (bufferedOut != null) {
                bufferedOut!!.flush()
                bufferedOut!!.close()  // 级联关闭底层加密流（finalize GCM 段）
                bufferedOut = null
                encryptedOutputStream = null
                encryptedFile = null
            }
            raf?.let {
                it.seek(0)
                writeWavHeader(it, totalBytes.toInt())
                it.close()
            }
            raf = null
            Log.i(TAG, "录音结束 totalBytes=$totalBytes (${totalBytes / 32}ms)")  // 与会议时长比对可算缺帧率
            totalBytes
        } catch (e: Exception) {
            Log.e(TAG, "关闭录音文件失败: ${e.message}")
            0
        }
    }

    fun cancel() {
        isRecording = false
        try {
            bufferedOut?.close()
        } catch (_: Exception) {}
        try {
            raf?.close()
        } catch (_: Exception) {}
        bufferedOut = null
        encryptedOutputStream = null
        encryptedFile = null
        raf = null
        outputFile.delete()
    }

    // ── MTEW 头 ──

    private fun buildMTEWHeader(): ByteArray {
        val buf = ByteBuffer.allocate(20).apply { order(ByteOrder.LITTLE_ENDIAN) }
        buf.put(MTEW_MAGIC)              // 4 bytes: "MTEW"
        buf.putInt(1)                    // 4 bytes: version
        buf.putInt(SAMPLE_RATE)          // 4 bytes: sample rate
        buf.putShort(BITS_PER_SAMPLE.toShort())  // 2 bytes: bits per sample
        buf.putShort(CHANNELS.toShort())         // 2 bytes: channels
        buf.putInt(0)                    // 4 bytes: reserved
        return buf.array()
    }

    // ── WAV 头 ──

    private fun writeWavHeader(raf: RandomAccessFile, dataSize: Int) {
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

        raf.write(buf.array())
    }
}
