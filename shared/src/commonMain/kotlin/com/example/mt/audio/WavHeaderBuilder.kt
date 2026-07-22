package com.example.mt.audio

/**
 * WAV 文件头构造器。
 *
 * 构造标准的 44 字节 PCM WAV 文件头：
 * - 采样率: 16kHz
 * - 位深: 16-bit signed PCM
 * - 声道: mono
 *
 * 写入策略：先写占位头 → 追加 PCM 数据 → 停止时回填文件大小。
 */
object WavHeaderBuilder {

    private const val SAMPLE_RATE = 16000
    private const val BITS_PER_SAMPLE = 16
    private const val NUM_CHANNELS = 1
    private const val BYTE_RATE = SAMPLE_RATE * NUM_CHANNELS * BITS_PER_SAMPLE / 8  // 32000
    private const val BLOCK_ALIGN = NUM_CHANNELS * BITS_PER_SAMPLE / 8  // 2

    /**
     * 构建 44 字节 WAV 文件头。
     * @param dataSize PCM 数据字节数，录制中未知时传 0
     * @return 44-byte RIFF/WAV header (little-endian)
     */
    fun build(dataSize: Int = 0): ByteArray {
        val header = ByteArray(44)
        val fileSize = dataSize + 36

        // RIFF chunk
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeInt32LE(header, 4, fileSize)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt sub-chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeInt32LE(header, 16, 16)     // sub-chunk size (PCM = 16)
        writeInt16LE(header, 20, 1)       // audio format (1 = PCM)
        writeInt16LE(header, 22, NUM_CHANNELS)
        writeInt32LE(header, 24, SAMPLE_RATE)
        writeInt32LE(header, 28, BYTE_RATE)
        writeInt16LE(header, 32, BLOCK_ALIGN)
        writeInt16LE(header, 34, BITS_PER_SAMPLE)

        // data sub-chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeInt32LE(header, 40, dataSize)

        return header
    }

    /** 用实际数据大小更新已有 WAV 头的 fileSize 和 dataSize 字段 */
    fun updateDataSize(header: ByteArray, dataSize: Int) {
        writeInt32LE(header, 4, dataSize + 36)
        writeInt32LE(header, 40, dataSize)
    }

    private fun writeInt32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeInt16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
