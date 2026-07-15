package com.example.meetingtranscriber.domain

import android.content.Context
import android.util.Log
import com.example.meetingtranscriber.engine.*
import com.example.meetingtranscriber.engine.asr.FunAsrCloudEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 转写用例 — 封装完整的「引擎解析 → 启动 → 推音频 → 收句 → 结束」生命周期。
 *
 * ViewModel 只需:
 * ```kotlin
 * useCase.setOnSentenceListener { sentence -> ... }
 * useCase.start(context, AsrConfig(language = "cn"))
 * // 每一帧:
 * useCase.processAudio(pcmData)
 * // 结束:
 * val transcript = useCase.stop()  // → List<AsrSentence>
 * ```
 */
class TranscriptionUseCase(
    private val engineRouter: EngineRouter
) {
    // ═══════════════════════════════════════════════════════════
    // 公开状态
    // ═══════════════════════════════════════════════════════════

    /** 当前使用的引擎 */
    private val _currentEngine = MutableStateFlow<AsrEngineType?>(null)
    val currentEngine: StateFlow<AsrEngineType?> = _currentEngine

    /** 引擎状态 */
    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    val engineStatus: StateFlow<EngineStatus> = _engineStatus

    /** 实时中间文本 */
    private val _interimText = MutableStateFlow("")
    val interimText: StateFlow<String> = _interimText

    /** 逐句转发引擎定稿句（替代原 StateFlow<List> 全列表快照：每句 list+sentence
     *  全量拷贝，2h 会议 1000+ 句时每句 O(n)，是长会议主线程卡顿源之一） */
    private val _sentenceFlow = MutableSharedFlow<AsrSentence>(extraBufferCapacity = 256)
    val sentenceFlow: SharedFlow<AsrSentence> = _sentenceFlow

    /** 累积句子（仅 stop() 返回用；追加 O(1)，不再每句复制整表） */
    private val sentenceList = ArrayList<AsrSentence>()

    /** 是否正在转写 */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    /** 最后一个错误 */
    private val _lastError = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val lastError: SharedFlow<String> = _lastError

    // ═══════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════

    private var engine: AsrEngine? = null
    private var sentenceCollectorJob: Job? = null
    private var scope: CoroutineScope? = null

    /**
     * 启动转写。
     *
     * @param context Application Context（用于引擎初始化）
     * @param config ASR 配置（语言、热词等）
     * @param engineTypeOverride 可选覆盖引擎类型（null = 自动路由）
     */
    suspend fun start(
        context: Context,
        config: AsrConfig = AsrConfig(language = "cn"),
        engineTypeOverride: AsrEngineType? = null
    ): Result<Unit> {
        // 防止重复启动
        if (_isRunning.value) {
            Log.w(TAG, "已有转写在运行，请先 stop()")
            return Result.failure(IllegalStateException("已有转写在运行"))
        }

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在解析引擎...")

            // 1. 解析引擎
            engine = engineRouter.resolveAsrEngine(context, engineTypeOverride)
            Log.i(TAG, "已解析引擎: ${engine!!.type.displayName}")
            _currentEngine.value = engine!!.type

            // 2. 初始化（幂等）
            val initResult = engine!!.initialize(context)
            if (initResult.isFailure) {
                val msg = "引擎初始化失败: ${initResult.exceptionOrNull()?.message}"
                Log.e(TAG, msg)
                _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
                _lastError.emit(msg)
                return Result.failure(initResult.exceptionOrNull()!!)
            }

            // 3. 启动
            val startResult = engine!!.start(config)
            if (startResult.isFailure) {
                val msg = "引擎启动失败: ${startResult.exceptionOrNull()?.message}"
                Log.e(TAG, msg)
                _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
                _lastError.emit(msg)
                return Result.failure(startResult.exceptionOrNull()!!)
            }

            // 4. 开始收集句子 + interim
            synchronized(sentenceList) { sentenceList.clear() }
            _interimText.value = ""
            _isRunning.value = true

            // 监听引擎状态
            sentenceCollectorJob = scope!!.launch {
                launch {
                    engine!!.interimText.collect { text ->
                        _interimText.value = text
                    }
                }
                launch {
                    engine!!.sentenceResults.collect { sentence ->
                        val count = synchronized(sentenceList) {
                            sentenceList.add(sentence)
                            sentenceList.size
                        }
                        _sentenceFlow.emit(sentence)
                        Log.d(TAG, "累计 $count 句")
                    }
                }
                launch {
                    engine!!.engineStatus.collect { status ->
                        _engineStatus.value = status
                    }
                }
            }

            Log.i(TAG, "转写已启动, engine=${engine!!.type.displayName}")
            return Result.success(Unit)
        } catch (e: NoEngineException) {
            val msg = "无可用的 ASR 引擎: ${e.message}"
            Log.e(TAG, msg)
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            _lastError.emit(msg)
            engine = null
            return Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "启动转写异常", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "异常: ${e.message}")
            _lastError.emit("异常: ${e.message}")
            engine = null
            return Result.failure(e)
        }
    }

    /**
     * 喂入一帧 PCM 音频。
     */
    fun processAudio(pcmData: ByteArray) {
        if (!_isRunning.value) return
        engine?.processAudio(pcmData)
    }

    /**
     * 结束转写，返回所有句子。
     *
     * @return 完整句子列表（可能为空）
     */
    suspend fun stop(): List<AsrSentence> = withContext(Dispatchers.IO) {
        // 先 finalize 冲刷残余流，再关闭 _isRunning 门
        // 否则 SharedFlow 中未消费的帧会被 processAudio() 拒绝，丢失 ~100-200ms 尾音
        try {
            engine?.finalize()
        } catch (e: Exception) {
            Log.e(TAG, "finalize 异常", e)
        }

        _isRunning.value = false

        // 等待最后的结果 flush
        delay(300)

        sentenceCollectorJob?.cancel()
        sentenceCollectorJob = null

        val result = synchronized(sentenceList) { sentenceList.toList() }
        Log.i(TAG, "转写结束，共 ${result.size} 句")

        // 释放引擎
        try {
            engine?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "dispose 异常", e)
        }

        engine = null
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        _interimText.value = ""
        _currentEngine.value = null
        scope?.cancel()
        scope = null

        result
    }

    /**
     * 切换说话人 — 通知引擎 decode 当前 utterance 并为下一说话人创建新 stream。
     * 由 VAD 沉默检测触发。返回结束轮次的 speakerId（不足 1s 跳过或云端引擎 → null）。
     */
    fun switchSpeaker(): String? {
        return (engine as? com.example.meetingtranscriber.engine.asr.FunAsrEngine)?.startNewSpeakerTurn()
    }

    /** 当前是否路由到本地 FunASR 引擎（声纹识别仅本地引擎启用） */
    fun isLocalFunAsr(): Boolean =
        engine is com.example.meetingtranscriber.engine.asr.FunAsrEngine

    /** VAD 人声状态透传给本地引擎（静音期跳过 interim decode 省 CPU） */
    fun setVoiceActive(active: Boolean) {
        (engine as? com.example.meetingtranscriber.engine.asr.FunAsrEngine)?.isVoiceActive = active
    }

    /** 会议结束前取当前轮次 speakerId（不切轮），供声纹判定最后一轮 */
    fun flushSpeakerTurn(): String? =
        (engine as? com.example.meetingtranscriber.engine.asr.FunAsrEngine)?.currentSpeakerId()

    /**
     * 取消转写（不保留结果）。
     */
    suspend fun cancel() {
        _isRunning.value = false
        sentenceCollectorJob?.cancel()
        sentenceCollectorJob = null

        try {
            engine?.dispose()
        } catch (_: Exception) {}

        engine = null
        synchronized(sentenceList) { sentenceList.clear() }
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        _currentEngine.value = null
        scope?.cancel()
        scope = null
        Log.i(TAG, "转写已取消")
    }

    /**
     * 动态切换方言（如果当前引擎支持）。
     * 部分云端引擎不支持运行时切换语言 — 调用方需处理 Result.failure。
     */
    suspend fun switchLanguage(languageCode: String): Result<Unit> {
        val current = engine ?: return Result.failure(IllegalStateException("没有活跃的引擎"))
        try {
            // 重启流
            current.finalize()
            val config = AsrConfig(language = languageCode)
            val result = current.start(config)
            if (result.isSuccess) {
                Log.i(TAG, "已切换语言: $languageCode")
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "切换语言失败", e)
            return Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "TranscriptionUseCase"
    }
}
