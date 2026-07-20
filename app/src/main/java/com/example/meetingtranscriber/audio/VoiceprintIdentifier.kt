package com.example.meetingtranscriber.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * 本地声纹识别：把 VAD 停顿切出的说话轮次映射为真实声纹身份。
 *
 * 用法：音频循环把语音帧 feed() 进来累积当前轮次；轮次结束（switchSpeaker
 * 真正切轮或会议结束）时 onTurnEnded()，后台计算声纹向量并与已知说话人
 * 匹配——命中复用旧标签，未命中注册"会议人K"，结果经 onIdentified 回调。
 *
 * 模型：CAM++ 中文声纹（assets/models/，26MB，16kHz 输入）。
 * 加载失败时 initialize 返回 false，整体禁用，行为回退为轮次递增。
 */
class VoiceprintIdentifier(private val scope: CoroutineScope) {

    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null

    /** 说话人 → 多条声纹样本（自实现余弦匹配，可拿到相似度分数便于调参） */
    private val speakers = LinkedHashMap<String, MutableList<FloatArray>>()

    /** 当前轮次 PCM 缓冲（feed/onTurnEnded 在音频循环线程，reset 跨协程） */
    private val bufferLock = Any()
    private val buffer = ByteArray(MAX_TURN_BYTES)
    private var bufferLen = 0

    /** 串行化 native embedding 计算与释放（extractor 线程安全性未知） */
    private val nativeMutex = Mutex()

    /** 专用低优先级单线程：embedding 不与 ASR decode 抢核（低端机关键） */
    private val embeddingDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voiceprint").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()

    /** 轮次身份判定结果回调：(turnSpeakerId, 身份标签)。
     *  label 为 null 表示无法判定（人声不足/推理异常），由调用方兜底。
     *  每次 onTurnEnded 必有且仅有一次回调。 */
    var onIdentified: ((turnSpeakerId: String, label: String?) -> Unit)? = null

    val isEnabled: Boolean get() = extractor != null

    /** 加载声纹模型（直接从 assets 读，无需拷贝）。失败返回 false → 整体禁用。 */
    fun initialize(context: Context): Boolean {
        if (extractor != null) return true
        return try {
            val ext = SpeakerEmbeddingExtractor(
                context.assets,
                SpeakerEmbeddingExtractorConfig(MODEL_ASSET_PATH, 1, false, "cpu"))
            extractor = ext
            Log.i(TAG, "声纹模型加载成功 (dim=${ext.dim()})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "声纹模型加载失败，降级为轮次递增: ${e.message}")
            extractor = null
            false
        }
    }

    /** 累积当前轮次的语音帧（超过 6s 上限则丢弃，已够算声纹） */
    fun feed(pcm: ByteArray) {
        if (extractor == null) return
        synchronized(bufferLock) {
            val n = minOf(pcm.size, MAX_TURN_BYTES - bufferLen)
            if (n > 0) {
                System.arraycopy(pcm, 0, buffer, bufferLen, n)
                bufferLen += n
            }
        }
    }

    /**
     * 轮次结束：快照并清空缓冲，后台判定该轮次说话人身份。
     * 人声不足 1s 声纹不可靠，回调 null 交由调用方兜底（如就近归属）。
     */
    fun onTurnEnded(turnSpeakerId: String) {
        if (extractor == null) return
        val snapshot: ByteArray
        synchronized(bufferLock) {
            snapshot = buffer.copyOf(bufferLen)
            bufferLen = 0
        }
        if (snapshot.size < MIN_TURN_BYTES) {
            Log.d(TAG, "$turnSpeakerId 人声不足 ${snapshot.size / BYTES_PER_MS}ms，交由调用方兜底")
            onIdentified?.invoke(turnSpeakerId, null)
            return
        }
        scope.launch(embeddingDispatcher) { identify(turnSpeakerId, snapshot) }
    }

    private suspend fun identify(turnSpeakerId: String, pcm: ByteArray) {
        nativeMutex.withLock {
            val ext = extractor ?: return@withLock
            try {
                val samples = FloatArray(pcm.size / 2)
                val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in samples.indices) samples[i] = shorts.get(i) / 32768.0f

                val stream = ext.createStream()
                val emb: FloatArray
                val t0 = System.currentTimeMillis()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    stream.inputFinished()
                    if (!ext.isReady(stream)) {
                        Log.w(TAG, "$turnSpeakerId 音频帧不足，交由调用方兜底")
                        onIdentified?.invoke(turnSpeakerId, null)
                        return@withLock
                    }
                    emb = ext.compute(stream)
                } finally {
                    stream.release()
                }
                val embeddingCost = System.currentTimeMillis() - t0

                // 余弦匹配：取每个说话人多样本中的最高分
                var bestName: String? = null
                var bestScore = -1f
                val scoreLog = StringBuilder()
                for ((name, embs) in speakers) {
                    val score = embs.maxOf { cosine(it, emb) }
                    scoreLog.append("$name=%.3f ".format(score))
                    if (score > bestScore) { bestScore = score; bestName = name }
                }
                val label: String
                if (bestName != null && bestScore >= THRESHOLD) {
                    label = bestName
                    // 多样本累积：命中后追加新声纹，样本越多越稳（上限 MAX_SAMPLES）
                    speakers[label]!!.let { if (it.size < MAX_SAMPLES) it.add(emb) }
                } else {
                    label = "会议人${speakers.size + 1}"
                    speakers[label] = mutableListOf(emb)
                }
                Log.d(TAG, "$turnSpeakerId (${pcm.size / BYTES_PER_MS}ms, embedding ${embeddingCost}ms) → $label " +
                        "[${scoreLog.toString().trim().ifEmpty { "首个说话人" }}, 阈值=$THRESHOLD]")
                onIdentified?.invoke(turnSpeakerId, label)
            } catch (e: Exception) {
                Log.e(TAG, "声纹识别异常: ${e.message}")
                onIdentified?.invoke(turnSpeakerId, null)
            }
        }
    }

    /** 余弦相似度（CAM++ 输出未归一化，需除模长） */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom > 0f) dot / denom else 0f
    }

    /** 新会议开始：清空缓冲并重建声纹分组（身份按会议隔离） */
    suspend fun reset() {
        synchronized(bufferLock) { bufferLen = 0 }
        nativeMutex.withLock { speakers.clear() }
    }

    /**
     * 仅释放 native 模型（27MB CAM++），保留线程池与回调，可再 initialize——
     * 会议结束后的内存编排用（纪要生成前腾出无用会话）。
     * 释放任务经声纹专用单线程队列排队：FIFO 保证已入队的最后一轮判定
     * 先跑完（此时 extractor 仍有效），之后才释放。
     */
    fun releaseModel() {
        if (extractor == null) return
        scope.launch(embeddingDispatcher) {
            nativeMutex.withLock {
                try { extractor?.release() } catch (_: Exception) {}
                extractor = null
            }
            Log.i(TAG, "声纹模型已释放（会后内存编排，下次开会重载）")
        }
    }

    /** 释放 native 资源。若识别任务仍在跑则跳过，交由 finalize() 兜底。 */
    fun release() {
        val ext = extractor
        extractor = null
        speakers.clear()
        if (nativeMutex.tryLock()) {
            try {
                ext?.release()
            } finally {
                nativeMutex.unlock()
            }
        }
        embeddingDispatcher.close()  // 已入队任务会跑完，之后线程退出
    }

    companion object {
        private const val TAG = "VoiceprintIdentifier"
        private const val MODEL_ASSET_PATH = "models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_MS = 32                       // 16kHz × 16bit / 8 / 1000
        private const val MIN_TURN_BYTES = SAMPLE_RATE * 2        // 1 秒（与 ASR 切轮跳过阈值一致）
        /** 6 秒上限：feed 只留轮次开头纯人声，CAM++ 在 3-6s 精度已接近饱和，
         *  比 15s 省 ~60% 计算量（低端机实测同人被拆多人再回调至 8s） */
        private const val MAX_TURN_BYTES = SAMPLE_RATE * 2 * 6
        /** 余弦相似度阈值：≥此值判定为同一人（分数见判定日志，可据此微调） */
        private const val THRESHOLD = 0.45f
        /** 每个说话人最多累积的声纹样本数 */
        private const val MAX_SAMPLES = 5
    }
}
