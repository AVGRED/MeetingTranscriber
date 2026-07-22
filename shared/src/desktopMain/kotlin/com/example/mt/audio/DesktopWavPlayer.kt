package com.example.mt.audio

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

actual class WavPlayer actual constructor() {

    private val _isPlaying = AtomicBoolean(false)
    actual val isPlaying: Boolean get() = _isPlaying.get()

    private var sourceLine: SourceDataLine? = null

    actual fun play(filePath: String): Boolean {
        if (_isPlaying.get()) return false
        val wavFile = File(filePath)
        if (!wavFile.exists() || !wavFile.isFile) return false

        return try {
            RandomAccessFile(wavFile, "r").use { raf ->
                if (raf.length() < 44) return false

                // 解析 WAV 头
                raf.seek(22)
                val channels = raf.readShortLE()
                val sampleRate = raf.readIntLE()
                raf.seek(34)
                val bitsPerSample = raf.readShortLE()
                raf.seek(40)
                val dataSize = raf.readIntLE()

                val pcmOffset = 44L
                val pcmLength = minOf(dataSize.toLong(), raf.length() - pcmOffset)
                if (pcmLength <= 0) return false

                val format = AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate.toFloat(),
                    bitsPerSample,
                    channels,
                    channels * bitsPerSample / 8,
                    sampleRate.toFloat(),
                    false
                )

                val info = DataLine.Info(SourceDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(info)) return false

                val line = AudioSystem.getSourceDataLine(format) as SourceDataLine
                line.open(format, 4096)
                line.start()
                sourceLine = line

                _isPlaying.set(true)

                // 以 4096 字节块推送 PCM 数据到声卡
                raf.seek(pcmOffset)
                val buffer = ByteArray(4096)
                var remaining = pcmLength
                while (remaining > 0 && _isPlaying.get()) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    line.write(buffer, 0, read)
                    remaining -= read
                }

                // 等待声卡播放完缓冲区
                line.drain()
                line.stop()
                line.close()
                sourceLine = null
                _isPlaying.set(false)
                true
            }
        } catch (e: Exception) {
            try { sourceLine?.stop(); sourceLine?.close() } catch (_: Exception) {}
            sourceLine = null
            _isPlaying.set(false)
            false
        }
    }

    actual fun stop() {
        _isPlaying.set(false)
        try { sourceLine?.stop(); sourceLine?.close() } catch (_: Exception) {}
        sourceLine = null
    }
}

private fun RandomAccessFile.readShortLE(): Int {
    val lo = readUnsignedByte()
    val hi = readUnsignedByte()
    return lo or (hi shl 8)
}

private fun RandomAccessFile.readIntLE(): Int {
    val b0 = readUnsignedByte()
    val b1 = readUnsignedByte()
    val b2 = readUnsignedByte()
    val b3 = readUnsignedByte()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}
