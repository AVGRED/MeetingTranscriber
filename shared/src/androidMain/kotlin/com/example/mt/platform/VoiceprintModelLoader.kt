package com.example.mt.platform

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android actual：从 assets 加载 sherpa-onnx CAM++ 声纹模型。
 */
actual class VoiceprintModelLoader actual constructor() {

    private var extractor: SpeakerEmbeddingExtractor? = null

    actual val isLoaded: Boolean get() = extractor != null

    actual fun load(): Boolean {
        if (extractor != null) return true
        val ctx = getAppContext() ?: return false
        return try {
            extractor = SpeakerEmbeddingExtractor(
                ctx.assets,
                SpeakerEmbeddingExtractorConfig(MODEL_ASSET_PATH, 1, false, "cpu")
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    actual fun extractEmbedding(pcm: ByteArray): FloatArray? {
        val ext = extractor ?: return null
        if (pcm.size < SAMPLE_RATE * 2) return null // 不足 1 秒
        return try {
            val samples = FloatArray(pcm.size / 2)
            val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in samples.indices) samples[i] = shorts.get(i) / 32768.0f

            val stream = ext.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                stream.inputFinished()
                if (!ext.isReady(stream)) null else ext.compute(stream)
            } finally {
                stream.release()
            }
        } catch (e: Exception) {
            null
        }
    }

    actual fun release() {
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
        private const val SAMPLE_RATE = 16000
    }
}
