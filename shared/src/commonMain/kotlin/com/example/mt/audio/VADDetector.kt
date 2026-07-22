package com.example.mt.audio

import kotlin.math.sqrt

/**
 * 简易能量阈值 VAD（语音活动检测）。
 *
 * 计算音频帧的 RMS 能量，低于阈值的视为静音帧。
 * 用滞回防止抖动：语音开始需要较高阈值，结束需要较低阈值。
 */
class VADDetector(
    private val onsetThreshold: Float = 300f,
    private val offsetThreshold: Float = 150f,
    private val silenceFrames: Int = 5,
) {

    private var isSpeech = false
    private var silenceCount = 0

    fun isVoice(pcmData: ByteArray): Boolean {
        val rms = calculateRMS(pcmData)

        if (isSpeech) {
            if (rms < offsetThreshold) {
                silenceCount++
                if (silenceCount >= silenceFrames) {
                    isSpeech = false
                    silenceCount = 0
                    return false
                }
            } else {
                silenceCount = 0
            }
            return true
        } else {
            if (rms > onsetThreshold) {
                isSpeech = true
                silenceCount = 0
                return true
            }
            return false
        }
    }

    private fun calculateRMS(pcmData: ByteArray): Float {
        val samples = pcmData.size / 2
        if (samples == 0) return 0f
        var sum = 0L
        for (i in pcmData.indices step 2) {
            if (i + 1 >= pcmData.size) break
            val sample = ((pcmData[i + 1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF)).toShort()
            sum += (sample * sample).toLong()
        }
        return sqrt(sum.toDouble() / samples).toFloat()
    }

    fun reset() {
        isSpeech = false
        silenceCount = 0
    }
}
