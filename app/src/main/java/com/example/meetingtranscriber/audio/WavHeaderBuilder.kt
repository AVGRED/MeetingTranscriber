package com.example.meetingtranscriber.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 标准 WAV (RIFF) 44 字节头构建器。
 *
 * 固定参数：16kHz / 16bit / mono — 所有录音与回放共享此格式。
 * WavRecorder 和 AudioCacheManager 均使用同一实现，消除重复。
 */
object WavHeaderBuilder {
    const val SAMPLE_RATE = 16000
    const val BITS_PER_SAMPLE = 16
    const val CHANNELS = 1

    /** 构建 44 字节 WAV 文件头，[dataSize] 为 PCM 数据字节数 */
    fun buildHeader(dataSize: Int): ByteArray {
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
