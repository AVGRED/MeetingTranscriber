package com.example.mt.audio

import io.github.aakira.napier.Napier
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

actual class WavRecorder actual constructor() {

    private val lock = Any()
    private var raf: RandomAccessFile? = null
    private val _isRecording = AtomicBoolean(false)
    actual val isRecording: Boolean get() = _isRecording.get()

    private var totalDataBytes = 0

    actual fun start(filePath: String): Boolean {
        if (_isRecording.get()) return false
        return try {
            val file = java.io.File(filePath)
            file.parentFile?.mkdirs()
            synchronized(lock) {
                raf = RandomAccessFile(file, "rw")
                // 写入占位 WAV 头（dataSize=0），稍后回填
                val header = WavHeaderBuilder.build(0)
                raf!!.write(header)
                totalDataBytes = 0
            }
            _isRecording.set(true)
            true
        } catch (e: Exception) {
            Napier.w("WavRecorder.start failed", e)
            synchronized(lock) {
                raf?.close()
                raf = null
            }
            false
        }
    }

    actual fun write(pcmData: ByteArray) {
        if (!_isRecording.get()) return
        synchronized(lock) {
            try {
                raf?.write(pcmData)
                totalDataBytes += pcmData.size
            } catch (e: Exception) {
                Napier.w("WavRecorder.write failed", e)
            }
        }
    }

    actual fun stop(): Boolean {
        if (!_isRecording.get()) return false
        _isRecording.set(false)
        return try {
            synchronized(lock) {
                raf?.let { file ->
                    // 回填 WAV 头中的文件大小和数据大小
                    file.seek(4)
                    file.writeIntLE(totalDataBytes + 36)
                    file.seek(40)
                    file.writeIntLE(totalDataBytes)
                    file.close()
                }
                raf = null
                totalDataBytes = 0
            }
            true
        } catch (e: Exception) {
            Napier.w("WavRecorder.stop failed", e)
            synchronized(lock) {
                try { raf?.close() } catch (_: Exception) {}
                raf = null
                totalDataBytes = 0
            }
            false
        }
    }

    /** 关闭录制器，释放资源（不保证正确停止，用于异常恢复） */
    fun release() {
        _isRecording.set(false)
        synchronized(lock) {
            try { raf?.close() } catch (_: Exception) {}
            raf = null
            totalDataBytes = 0
        }
    }
}

private fun RandomAccessFile.writeIntLE(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
}
