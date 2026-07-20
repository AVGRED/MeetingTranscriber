package com.example.meetingtranscriber.engine.asr

import android.content.Context
import android.util.Log
import com.example.meetingtranscriber.engine.*
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 离线 ASR 引擎 — sherpa-onnx SenseVoiceSmall (OfflineRecognizer)。
 *
 * **硬约束**:
 * 1. OfflineRecognizer 是批处理 API，同一 stream 只能 decode 一次
 * 2. 因此不能"定期 decode 看 interim 再继续累积"——reset 后之前音频丢失
 *
 * **双轨策略**:
 * - 主 stream: 只累积，VAD 停顿 / 软停顿智能截断（≥4s 且 VAD 静音，即呼吸
 *   停顿处落刀）/ 8s 硬截断 / 会议结束 → decode → 最终句子。
 *   软/硬截断切在语流中间，下一句标记 isContinuation，UI 层合并显示
 * - interim: 当前句累积音频（切句时清空，容量 8s 与硬截断对齐）→ 独立
 *   stream decode → interimText，预览显示完整当前句而非片段
 *   主 stream 和 interim stream 互不干扰，interim 提供实时出字体验
 *
 * interim 自适应节流: 每次 decode 后休眠 max(250ms−耗时, 耗时×2)，
 * 快机整周期 ~250ms，弱机自动降频（interim 占 CPU ≤1/3）；
 * 静音期与新音频不足 0.2s 时直接跳过
 */
class FunAsrEngine(private val context: Context) : AsrEngine {

    override val type: AsrEngineType = AsrEngineType.FUNASR_LOCAL

    private val engineLock = Any()
    private val decodeLock = Any()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var stream: OfflineStream? = null

    private val _interimText = MutableStateFlow("")
    override val interimText: StateFlow<String> = _interimText

    private val _sentenceResults = MutableSharedFlow<AsrSentence>(extraBufferCapacity = 64)
    override val sentenceResults: SharedFlow<AsrSentence> = _sentenceResults

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus

    private var totalBytesProcessed = 0L
    private var sentenceIndex = 0L
    private var lastResetBytes = 0L
    @Volatile private var currentSpeakerIndex = 0
    /** 硬截断上限 8s：连续语音找不到软切点时的兜底（可能切在词中间） */
    private val maxUtteranceBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * 8
    /** ≥4s 起进入"找刀点"模式：遇 VAD 静音（≥0.25s 呼吸停顿）即软切，不切坏词 */
    private val softCutMinBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * 4
    /** 上一句因软/硬截断在语流中间收尾 → 下一句是它的续段 */
    private var pendingContinuation = false

    // ── 滑动窗口 interim decode ──
    private val recentChunks = ArrayDeque<FloatArray>()  // 存 float，decodeInterim 免重复转换
    private var recentSamples = 0L
    private val maxRecentSamples = SAMPLE_RATE * 8L  // 当前句全量（切句清空，8s 与硬截断对齐）
    private val interimIntervalMs = 250L
    private val minNewAudioBytes = SAMPLE_RATE * BYTES_PER_SAMPLE / 5  // 新音频不足 0.2s 跳过
    private var interimScope: CoroutineScope? = null
    private var interimJob: Job? = null

    /** VAD 人声状态（由 ViewModel 透传），静音期跳过 interim decode 省 CPU */
    @Volatile var isVoiceActive = true
    private var lastInterimBytes = 0L
    @Volatile private var silenceSkipLogged = false

    // ── final decode 异步化：换流在锁内瞬间完成，decode 由专属消费协程串行执行 ──
    // 低端机单次 decode 1-3s，同步跑在音频采集链路上会丢帧（连录音存档都缺）
    private data class PendingDecode(
        val stream: OfflineStream,
        val sentenceId: Long,     // 入队时分配，Channel FIFO + 单消费者保证句序
        val speakerId: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val isContinuation: Boolean
    )
    private var decodeChannel: Channel<PendingDecode>? = null
    private var decodeScope: CoroutineScope? = null
    private var decodeConsumerJob: Job? = null

    // ═══════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════

    override suspend fun initialize(context: Context): Result<Unit> {
        val s = _engineStatus.value
        if (s.state == EngineState.READY || s.state == EngineState.RUNNING)
            return Result.success(Unit)

        return withContext(Dispatchers.IO) {
            synchronized(engineLock) {
                if (_engineStatus.value.state.let { it == EngineState.READY || it == EngineState.RUNNING })
                    return@withContext Result.success(Unit)

                _engineStatus.value = EngineStatus(EngineState.LOADING, "正在加载语音模型...")
                try {
                    // AssetManager 直读（.onnx 已 noCompress）：免 237MB assets→filesDir
                    // 拷贝（首次开会慢闪存 10s+）与双份存储；sherpa-onnx JNI 原生支持
                    Log.i(TAG, "从 assets 直读模型: $ASSET_MODEL_PATH/$MODEL_FILE_NAME")

                    recognizer = OfflineRecognizer(context.assets, OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = "$ASSET_MODEL_PATH/$MODEL_FILE_NAME", language = "auto"),
                            tokens = "$ASSET_MODEL_PATH/$TOKENS_FILE_NAME",
                            // 按核数取 2~4：decode 由 decodeLock 串行化，同一时刻只有
                            // 一个 decode 在跑，多线程不叠加；8 核机 4 线程可显著缩短
                            // interim/final 单次 decode 耗时（出字节奏直接受益）
                            numThreads = (Runtime.getRuntime().availableProcessors() / 2)
                                .coerceIn(2, 4),
                            provider = "cpu", debug = false)))
                    _engineStatus.value = EngineStatus(EngineState.READY, "模型已就绪")
                    Log.i(TAG, "初始化成功")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "初始化失败", e)
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "模型加载失败: ${e.message}")
                    Result.failure(e)
                }
            }
        }
    }

    override suspend fun start(config: AsrConfig): Result<Unit> {
        return withContext(Dispatchers.IO) {
            synchronized(engineLock) {
                val rec = recognizer ?: return@withContext Result.failure(
                    IllegalStateException("引擎未初始化"))
                try {
                    stream = rec.createStream()
                    totalBytesProcessed = 0L
                    lastResetBytes = 0L
                    sentenceIndex = 0L
                    currentSpeakerIndex = 0
                    _interimText.value = ""
                    recentChunks.clear()
                    recentSamples = 0L
                    lastInterimBytes = 0L
                    isVoiceActive = true
                    silenceSkipLogged = false
                    pendingContinuation = false
                    _engineStatus.value = EngineStatus(EngineState.RUNNING)

                    // 启动 final decode 消费协程（与 interim 分开：finalize 只停
                    // interim，消费协程须活到收尾把最后一句解完）
                    decodeScope?.cancel()
                    // 容量 16：每积压 stream 持最多 8s native 音频 ≈512KB，上限 ~8MB；
                    // 4 太紧，低端机连续快语 + IO 抖动会误丢句
                    decodeChannel = Channel(capacity = 16)
                    decodeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                    decodeConsumerJob = decodeScope!!.launch {
                        for (pending in decodeChannel!!) {
                            consumePendingDecode(pending)
                        }
                    }

                    // 启动滑动窗口 interim decode 协程（自适应节流，见类注释）
                    interimScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                    interimJob = interimScope!!.launch {
                        while (isActive) {
                            var decoded = false
                            val cost = kotlin.system.measureTimeMillis {
                                decoded = decodeInterim()
                            }
                            if (decoded) Log.d(TAG, "interim decode ${cost}ms")
                            // 周期补偿：目标整周期（decode+休眠）≈250ms；
                            // cost×2 下限保证 interim 占 CPU 仍 ≤1/3，弱机自动降频
                            delay(maxOf(interimIntervalMs - cost, cost * 2))
                        }
                    }

                    Log.i(TAG, "Stream 已创建, interim 自适应节流（基准 ${interimIntervalMs}ms）")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "Stream 创建失败", e)
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "启动失败: ${e.message}")
                    Result.failure(e)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // processAudio — 累积 + 喂入滑动窗口
    // ═══════════════════════════════════════════════════════════

    override fun processAudio(pcmData: ByteArray) {
        if (_engineStatus.value.state != EngineState.RUNNING) return
        try {
            val samples = pcmToFloatArray(pcmData)
            synchronized(engineLock) {
                val s = stream ?: return
                s.acceptWaveform(samples, SAMPLE_RATE)
                totalBytesProcessed += pcmData.size

                // 喂入滑动窗口（直接存转换好的 float，interim 免重复转换）
                recentChunks.addLast(samples)
                recentSamples += samples.size
                while (recentSamples > maxRecentSamples && recentChunks.isNotEmpty()) {
                    recentSamples -= recentChunks.removeFirst().size
                }

                // 语流中截断：≥4s 遇软停顿（VAD 静音 = 0.25s+ 呼吸间隙）落刀，
                // 8s 硬上限兜底；均异步 decode，不阻塞采集链路
                val utterBytes = totalBytesProcessed - lastResetBytes
                if (utterBytes >= maxUtteranceBytes ||
                    (utterBytes >= softCutMinBytes && !isVoiceActive)) {
                    val rec = recognizer ?: return
                    enqueueFinalDecode(rec, createNext = true, midUtteranceCut = true)
                    Log.d(TAG, (if (utterBytes >= maxUtteranceBytes) "硬截断 " else "软截断 ") +
                            "${utterBytes / BYTES_PER_MS}ms @${totalBytesProcessed / BYTES_PER_MS}ms")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processAudio 异常", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "音频处理异常: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 滑动窗口 interim decode
    // ═══════════════════════════════════════════════════════════

    /** @return 是否实际执行了 decode（跳过返回 false，调用方据此节流/打日志） */
    private fun decodeInterim(): Boolean {
        // 静音期窗口里只有底噪，decode 纯浪费
        if (!isVoiceActive) {
            if (!silenceSkipLogged) {
                Log.d(TAG, "interim 跳过（静音）")
                silenceSkipLogged = true
            }
            return false
        }
        silenceSkipLogged = false

        // 快照 chunk 引用列表 + recognizer（持锁极短，不阻塞 processAudio）。
        // 只拷贝 ~80 个数组引用，不再拼接 512KB FloatArray（每 250ms 一次的
        // 大块分配 = 低端机 GC 抖动源）；锁外对 interim stream 逐块 acceptWaveform
        val chunks: List<FloatArray>
        val rec: OfflineRecognizer?
        synchronized(engineLock) {
            if (recentChunks.isEmpty() || recentSamples < SAMPLE_RATE * 3 / 10) {
                return false  // 不足 0.3s 不解码（首字延迟的下限）
            }
            if (totalBytesProcessed - lastInterimBytes < minNewAudioBytes) {
                return false  // 新音频不足 0.2s，窗口几乎没变
            }
            rec = recognizer
            if (rec == null || _engineStatus.value.state != EngineState.RUNNING) return false
            chunks = recentChunks.toList()
            lastInterimBytes = totalBytesProcessed
        }

        try {
            var text = ""
            synchronized(decodeLock) {
                // 二次确认：dispose() 在 decodeLock 内释放并置空 recognizer；
                // 只查状态不够（IDLE 是出锁后才置的），必须查字段本身
                if (recognizer == null || _engineStatus.value.state != EngineState.RUNNING) return false
                val interimStream = rec!!.createStream()
                try {
                    for (chunk in chunks) interimStream.acceptWaveform(chunk, SAMPLE_RATE)
                    rec!!.decode(interimStream)
                    text = rec!!.getResult(interimStream).text.trim()
                } finally {
                    interimStream.release()
                }
            }
            if (text.isNotBlank()) {
                _interimText.value = text
            }
        } catch (e: Exception) {
            Log.w(TAG, "interim decode 失败: ${e.message}")
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════
    // 说话人切换 & 结束
    // ═══════════════════════════════════════════════════════════

    /** VAD 停顿后触发。音频不足 1s 则跳过。返回结束轮次的 speakerId，跳过返回 null。 */
    fun startNewSpeakerTurn(): String? {
        synchronized(engineLock) {
            if (stream == null) return null
            val rec = recognizer ?: return null
            val elapsed = totalBytesProcessed - lastResetBytes
            if (elapsed < SAMPLE_RATE * BYTES_PER_SAMPLE) {
                if (!pendingContinuation) {
                    Log.d(TAG, "startNewSpeakerTurn 跳过（仅 ${elapsed / BYTES_PER_MS}ms）")
                    return null
                }
                // 软切后停顿成真：正文已随软切出句，当前流只剩停顿静音——
                // 免 decode 直接推进轮次，否则下一位发言人会沿用本轮身份；
                // 停顿即句子自然收尾，下一句不再是续段
                pendingContinuation = false
                val endedSpeakerId = "speaker_$currentSpeakerIndex"
                currentSpeakerIndex++
                Log.d(TAG, "软切后停顿成真，免 decode 切轮 → speaker_$currentSpeakerIndex")
                return endedSpeakerId
            }
            enqueueFinalDecode(rec, createNext = true)
            val endedSpeakerId = "speaker_$currentSpeakerIndex"
            currentSpeakerIndex++
            Log.d(TAG, "说话人切换 → speaker_$currentSpeakerIndex")
            return endedSpeakerId
        }
    }

    /** 当前轮次的 speakerId（会议结束时声纹 flush 最后一轮用，不切轮） */
    fun currentSpeakerId(): String = "speaker_$currentSpeakerIndex"

    override suspend fun finalize() {
        // 先停 interim 协程，避免与最后的 final decode 竞争
        interimJob?.cancel()
        interimJob = null
        interimScope?.cancel()
        interimScope = null

        synchronized(engineLock) {
            val rec = recognizer
            if (rec != null && stream != null) {
                // 最后一段无条件出（含不足 1s 的尾音）
                enqueueFinalDecode(rec, createNext = false)
            } else {
                try { stream?.release() } catch (_: Exception) {}
                stream = null
            }
        }

        // 关闭队列并等消费完：保证最后一句发出后才返回（stop() 依赖此时序）
        decodeChannel?.close()
        try {
            withTimeoutOrNull(30_000) { decodeConsumerJob?.join() }
        } catch (e: Exception) {
            Log.e(TAG, "finalize 等待 decode 异常: ${e.message}")
        }
        Log.i(TAG, "finalize 完成，共 $sentenceIndex 句")
    }

    override suspend fun dispose() {
        interimJob?.cancel()
        interimJob = null
        interimScope?.cancel()
        interimScope = null

        // 停消费协程并清空积压（未消费的 stream 直接释放，cancel 场景不需要结果）
        decodeChannel?.close()
        decodeConsumerJob?.cancel()
        while (true) {
            val p = decodeChannel?.tryReceive()?.getOrNull() ?: break
            try { p.stream.release() } catch (_: Exception) {}
        }
        decodeScope?.cancel()
        decodeChannel = null
        decodeConsumerJob = null
        decodeScope = null

        withContext(Dispatchers.IO) {
            synchronized(engineLock) {
                synchronized(decodeLock) {
                    try { stream?.release() } catch (_: Exception) {}
                    stream = null
                    // 模型常驻策略按可用内存自适应：
                    // - 内存充裕 → 常驻（重载 226MB 模型需 3-4s，是"开始会议卡很久"的主因）
                    // - 可用 <1.5GB 或系统 lowMemory → 释放换内存，下次开会重载
                    //   （有 LOADING 横幅反馈，不再是无提示假死）
                    // recognizer 的释放必须在 decodeLock 内：interim/final decode
                    // 均在此锁内二次确认 recognizer 非空
                    if (recognizer != null && isMemoryPressure()) {
                        Log.i(TAG, "内存紧张，释放 ASR 模型（下次开会重载）")
                        try { recognizer?.release() } catch (_: Exception) {}
                        recognizer = null
                    }
                }
                // initialize() 幂等，下次直达 READY；进程退出由系统回收
                _engineStatus.value = if (recognizer != null)
                    EngineStatus(EngineState.READY, "模型已就绪")
                else EngineStatus(EngineState.IDLE)
                Log.i(TAG, if (recognizer != null) "会话已释放（模型常驻）" else "会话与模型均已释放")
            }
        }
    }

    /** 可用内存是否已紧张（dispose 时决定模型是否退常驻） */
    private fun isMemoryPressure(): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.lowMemory || mi.availMem < LOW_MEM_RELEASE_THRESHOLD
        } catch (_: Exception) {
            false
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════

    /**
     * 持 engineLock 调用：摘下当前 stream 打包入队（decode 由消费协程异步执行），
     * 并视需要换新流。锁内只做对象交接，毫秒级完成。
     *
     * @param midUtteranceCut 本次是语流中截断（软/硬切）——下一句将标记为本句续段
     */
    private fun enqueueFinalDecode(
        rec: OfflineRecognizer,
        createNext: Boolean,
        midUtteranceCut: Boolean = false
    ) {
        val s = stream ?: return
        sentenceIndex++
        val pending = PendingDecode(
            stream = s,
            sentenceId = sentenceIndex,
            speakerId = "speaker_$currentSpeakerIndex",
            startTimeMs = lastResetBytes / BYTES_PER_MS,
            endTimeMs = totalBytesProcessed / BYTES_PER_MS,
            isContinuation = pendingContinuation
        )
        pendingContinuation = midUtteranceCut
        stream = if (createNext) rec.createStream() else null
        lastResetBytes = totalBytesProcessed
        // 不清 _interimText：final decode 低端机需 1-3s，先清会让当前句文字
        // "消失又重现"（视觉倒退）。定稿句上屏时由 ViewModel 清 interim；
        // 空白句（噪声幻觉不发射）由 consumePendingDecode 兜底清
        recentChunks.clear()
        recentSamples = 0L
        val sent = decodeChannel?.trySend(pending)
        if (sent?.isSuccess != true) {
            // 积压 16 段还没消费完 = 实时率已崩，丢弃防止无限堆积 native 内存
            Log.e(TAG, "decode 队列满，丢弃句${pending.sentenceId}")
            try { pending.stream.release() } catch (_: Exception) {}
        }
    }

    /** 消费协程串行执行：decode + 发射句子 + 释放 stream */
    private fun consumePendingDecode(pending: PendingDecode) {
        try {
            val rec = recognizer ?: return
            var text = ""
            val cost = kotlin.system.measureTimeMillis {
                synchronized(decodeLock) {
                    // 二次确认：dispose() 在 decodeLock 内释放并置空 recognizer，
                    // 锁外拿到的 rec 可能已失效，直接 decode 会踩已释放的 native 指针
                    if (recognizer == null) return
                    rec.decode(pending.stream)
                    text = rec.getResult(pending.stream).text.trim()
                }
            }
            Log.d(TAG, "final decode ${cost}ms (句${pending.sentenceId}, " +
                    "${pending.endTimeMs - pending.startTimeMs}ms 音频)")
            if (text.isBlank()) {
                // 空白句不发射 → 不会走 ViewModel 落段清屏，这里兜底清掉残留预览
                _interimText.value = ""
                return
            }
            val ok = _sentenceResults.tryEmit(AsrSentence(
                text = text, sentenceId = pending.sentenceId,
                speakerId = pending.speakerId,
                startTimeMs = pending.startTimeMs,
                endTimeMs = pending.endTimeMs, isFinal = true,
                isContinuation = pending.isContinuation))
            if (!ok) Log.w(TAG, "emitSentence: buffer 满，句子丢弃")
        } catch (e: Exception) {
            Log.e(TAG, "final decode 异常: ${e.message}")
        } finally {
            try { pending.stream.release() } catch (_: Exception) {}
        }
    }

    private fun pcmToFloatArray(pcmData: ByteArray): FloatArray {
        val n = pcmData.size / BYTES_PER_SAMPLE
        val samples = FloatArray(n)
        val buf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        buf.rewind()
        for (i in 0 until n) samples[i] = buf[i].toFloat() / 32768f
        return samples
    }

    companion object {
        private const val TAG = "FunAsrEngine"
        const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_MS = (SAMPLE_RATE * BYTES_PER_SAMPLE) / 1000
        const val MODEL_DIR = "models"
        const val ASSET_MODEL_PATH = "models"
        const val MODEL_FILE_NAME = "sense-voice-small-cn.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"
        /** dispose 时可用内存低于此值则释放模型退常驻（换取纪要生成/前台内存余量） */
        private const val LOW_MEM_RELEASE_THRESHOLD = 1_500L * 1024 * 1024
    }
}
