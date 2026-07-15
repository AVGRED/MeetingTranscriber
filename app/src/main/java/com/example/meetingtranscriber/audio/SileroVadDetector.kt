package com.example.meetingtranscriber.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Silero 神经网络 VAD（sherpa-onnx 内置支持，模型 ~0.6MB）。
 *
 * 替代能量阈值 VAD 做说话人切轮的停顿检测：能量 VAD 会被手机麦克风
 * 自动增益（AGC）抬高的底噪骗过——人停顿后底噪 RMS 仍超阈值，导致
 * 永远检测不到停顿、永不切轮。Silero 判断"是否人声"而非"音量大小"，
 * 不受 AGC/环境噪声影响。
 *
 * 接口与 [VADDetector] 对齐（isVoice/reset），加载失败由 create() 返回
 * null，调用方回退能量 VAD。
 */
class SileroVadDetector private constructor(private val vad: Vad) {

    // 帧长固定 3200B（AudioCaptureManager.CHUNK_SIZE），复用缓冲避免每帧分配
    private var samples = FloatArray(1600)

    /** 输入 16kHz/16bit 单声道 PCM 帧，返回当前是否处于人声状态 */
    fun isVoice(pcmData: ByteArray): Boolean {
        val n = pcmData.size / 2
        if (samples.size != n) samples = FloatArray(n)
        val shorts = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until n) samples[i] = shorts.get(i) / 32768.0f
        vad.acceptWaveform(samples)
        // 清理内部积累的分段队列，防止长会议内存增长（只用实时状态，不用分段结果）
        while (!vad.empty()) vad.pop()
        return vad.isSpeechDetected()
    }

    fun reset() = vad.reset()

    fun release() = vad.release()

    companion object {
        private const val TAG = "SileroVadDetector"
        private const val MODEL_ASSET_PATH = "models/silero_vad.onnx"

        /** 从 assets 加载模型，失败返回 null（调用方回退能量 VAD） */
        fun create(context: Context): SileroVadDetector? {
            return try {
                val vad = Vad(context.assets, VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = MODEL_ASSET_PATH,
                        threshold = 0.4f,             // 稍低于默认 0.5，提高远场/低音量灵敏度
                        minSilenceDuration = 0.25f,   // 0.25s 静音判定语音结束（外层还有切轮门槛）
                        minSpeechDuration = 0.1f,
                        windowSize = 512,
                        maxSpeechDuration = 20.0f
                    ),
                    sampleRate = 16000,
                    numThreads = 1,
                    provider = "cpu"
                ))
                Log.i(TAG, "Silero VAD 加载成功")
                SileroVadDetector(vad)
            } catch (e: Exception) {
                Log.w(TAG, "Silero VAD 加载失败，回退能量 VAD: ${e.message}")
                null
            }
        }
    }
}
