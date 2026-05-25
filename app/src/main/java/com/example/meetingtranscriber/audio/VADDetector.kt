package com.example.meetingtranscriber.audio

import kotlin.math.sqrt

/**
 * 简易能量阈值 VAD（语音活动检测）
 *
 * 计算音频帧的 RMS 能量，低于阈值的视为静音帧。
 * 用滞回防止抖动：语音开始需要较高阈值，结束需要较低阈值。
 *
 * 注意：MVP 阶段用能量检测即可，准确的 VAD 由云端 ASR 保证。
 * 端侧 VAD 的主要作用是减少上传静音段，降低带宽浪费。
 */
class VADDetector(
    private val onsetThreshold: Float = 300f,    // 语音开始阈值
    private val offsetThreshold: Float = 150f,   // 语音结束阈值
    private val silenceFrames: Int = 5           // 连续静音多少帧才认为语音结束
) {

    private var isSpeech = false
    private var silenceCount = 0

    /**
     * 检测一帧是否为语音
     * @param pcmData 16bit PCM 音频数据
     * @return true = 语音帧, false = 静音帧
     */
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

    /** 计算 RMS (Root Mean Square) */
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
