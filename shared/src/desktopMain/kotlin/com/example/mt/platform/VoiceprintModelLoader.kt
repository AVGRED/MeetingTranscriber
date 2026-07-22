package com.example.mt.platform

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

actual class VoiceprintModelLoader actual constructor() {

    private var extractor: SpeakerEmbeddingExtractor? = null

    actual val isLoaded: Boolean get() = extractor != null

    actual fun load(): Boolean {
        if (extractor != null) return true
        val modelPath = resolveModelPath() ?: return false
        return try {
            extractor = SpeakerEmbeddingExtractor(
                modelPath,
                SpeakerEmbeddingExtractorConfig(
                    model = modelPath,
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                )
            )
            true
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("VoiceprintModelLoader: 加载 sherpa-onnx native DLL 失败，请确保 DLL 在 java.library.path 中")
            false
        } catch (e: Exception) {
            System.err.println("VoiceprintModelLoader: 加载模型失败 ${e.message}")
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

    private fun resolveModelPath(): String? {
        val modelDir = File(System.getProperty("user.home"), "MeetingTranscriber/models")
        if (!modelDir.exists()) modelDir.mkdirs()

        // 按优先级查找声纹模型文件
        val candidates = listOf(
            "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
            "speaker_campplus.onnx",
            "campplus.onnx"
        )
        for (name in candidates) {
            val file = File(modelDir, name)
            if (file.exists() && file.isFile) return file.absolutePath
        }

        // 兜底：查找 models 目录下任意 .onnx 文件
        val anyOnnx = modelDir.listFiles()?.firstOrNull {
            it.isFile && it.name.endsWith(".onnx")
        }
        return anyOnnx?.absolutePath
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}
