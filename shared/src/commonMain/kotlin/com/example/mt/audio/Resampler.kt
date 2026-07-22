package com.example.mt.audio

/**
 * 轻量级音频重采样器。
 *
 * 用于 TV 等硬件只支持 48kHz 立体声的设备：
 * - 声道下混：立体声 → 单声道 (L+R)/2
 * - 采样率转换：48kHz → 16kHz（factor=3 抽取）
 *
 * 算法简单直接，ASR 场景音质足够：
 * - 下混：(L+R)/2（已转为 Int 避免溢出）
 * - 抽取：每 3 个立体声采样点取 1 个（subsample）
 *
 * 输入 (100ms @ 48kHz stereo): 19200 bytes → 输出 (100ms @ 16kHz mono): 3200 bytes
 */
object Resampler {

    /** 每 3 组立体声帧的字节数 = 3 × (L2B + R2B) = 12 */
    private const val BYTES_PER_GROUP = 12

    /**
     * 48kHz 16-bit 立体声 → 16kHz 16-bit 单声道。
     *
     * 每 3 个立体声采样对 (12 bytes) 产生 1 个单声道采样 (2 bytes)：
     * - 3 组中只取第 1 组的 L+R 下混，跳过后 2 组（3× 抽取）
     *
     * @param input 原始 PCM 字节数组（little-endian 16-bit 有符号）
     * @return 重采样后的 PCM 字节数组
     */
    fun resample48kStereoTo16kMono(input: ByteArray): ByteArray {
        val totalGroups = input.size / BYTES_PER_GROUP
        val output = ByteArray(totalGroups * 2)

        var inIdx = 0
        var outIdx = 0

        while (inIdx + BYTES_PER_GROUP <= input.size) {
            val l = ((input[inIdx].toInt() and 0xFF)
                    or ((input[inIdx + 1].toInt() and 0xFF) shl 8)).toShort()
            val r = ((input[inIdx + 2].toInt() and 0xFF)
                    or ((input[inIdx + 3].toInt() and 0xFF) shl 8)).toShort()

            // 使用整数除法（截断向零）避免 shr 对负数的向下取整偏置
            val mono = ((l.toInt() + r.toInt()) / 2).toShort()

            output[outIdx] = (mono.toInt() and 0xFF).toByte()
            output[outIdx + 1] = ((mono.toInt() shr 8) and 0xFF).toByte()

            outIdx += 2
            inIdx += BYTES_PER_GROUP
        }

        return output
    }
}
