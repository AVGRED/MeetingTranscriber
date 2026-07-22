package com.example.mt.domain

import com.example.mt.engine.*
import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 转写用例 — 封装完整的「引擎解析 → 启动 → 推音频 → 收句 → 结束」生命周期（跨平台）。
 */
class TranscriptionUseCase(
    private val engineRouter: EngineRouter,
) {

    private val _currentEngine = MutableStateFlow<AsrEngineType?>(null)
    val currentEngine: StateFlow<AsrEngineType?> = _currentEngine

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    val engineStatus: StateFlow<EngineStatus> = _engineStatus

    private val _interimText = MutableStateFlow("")
    val interimText: StateFlow<String> = _interimText

    private val _sentenceFlow = MutableSharedFlow<AsrSentence>(extraBufferCapacity = 256)
    val sentenceFlow: SharedFlow<AsrSentence> = _sentenceFlow

    private val sentenceList = ArrayList<AsrSentence>()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _lastError = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val lastError: SharedFlow<String> = _lastError

    private var engine: AsrEngine? = null
    private var sentenceCollectorJob: Job? = null
    private var scope: CoroutineScope? = null

    suspend fun start(
        config: AsrConfig = AsrConfig(language = "cn"),
        engineTypeOverride: AsrEngineType? = null,
    ): Result<Unit> {
        if (_isRunning.value) {
            Napier.w("$TAG: 已有转写在运行，请先 stop()")
            return Result.failure(IllegalStateException("已有转写在运行"))
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在解析引擎...")

            engine = engineRouter.resolveAsrEngine(engineTypeOverride)
            Napier.i("$TAG: 已解析引擎: ${engine!!.type.displayName}")
            _currentEngine.value = engine!!.type

            val initResult = engine!!.initialize()
            if (initResult.isFailure) {
                val msg = "引擎初始化失败: ${initResult.exceptionOrNull()?.message}"
                Napier.e("$TAG: $msg")
                _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
                return Result.failure(initResult.exceptionOrNull()!!)
            }

            val startResult = engine!!.start(config)
            if (startResult.isFailure) {
                val msg = "引擎启动失败: ${startResult.exceptionOrNull()?.message}"
                Napier.e("$TAG: $msg")
                _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
                return Result.failure(startResult.exceptionOrNull()!!)
            }

            synchronized(sentenceList) { sentenceList.clear() }
            _interimText.value = ""
            _isRunning.value = true

            sentenceCollectorJob = scope!!.launch {
                launch {
                    engine!!.interimText.collect { _interimText.value = it }
                }
                launch {
                    engine!!.sentenceResults.collect { sentence ->
                        val count = synchronized(sentenceList) {
                            sentenceList.add(sentence)
                            sentenceList.size
                        }
                        _sentenceFlow.emit(sentence)
                        Napier.d("$TAG: 累计 $count 句")
                    }
                }
                launch {
                    engine!!.engineStatus.collect { _engineStatus.value = it }
                }
            }

            Napier.i("$TAG: 转写已启动, engine=${engine!!.type.displayName}")
            return Result.success(Unit)
        } catch (e: NoEngineException) {
            Napier.e("$TAG: 无可用的 ASR 引擎: ${e.message}")
            _engineStatus.value = EngineStatus(EngineState.ERROR, e.message ?: "无可用引擎")
            engine = null
            return Result.failure(e)
        } catch (e: Exception) {
            Napier.e("$TAG: 启动转写异常", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "异常: ${e.message}")
            engine = null
            return Result.failure(e)
        }
    }

    fun processAudio(pcmData: ByteArray) {
        if (!_isRunning.value) return
        engine?.processAudio(pcmData)
    }

    suspend fun stop(): List<AsrSentence> = withContext(Dispatchers.Default) {
        try { engine?.finalize() } catch (e: Exception) {
            Napier.e("$TAG: finalize 异常", e)
        }

        _isRunning.value = false
        delay(300)

        sentenceCollectorJob?.cancel()
        sentenceCollectorJob = null

        val result = synchronized(sentenceList) { sentenceList.toList() }
        Napier.i("$TAG: 转写结束，共 ${result.size} 句")

        try { engine?.dispose() } catch (e: Exception) {
            Napier.e("$TAG: dispose 异常", e)
        }

        engine = null
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        _interimText.value = ""
        _currentEngine.value = null
        scope?.cancel()
        scope = null

        result
    }

    suspend fun cancel() {
        _isRunning.value = false
        sentenceCollectorJob?.cancel()
        sentenceCollectorJob = null

        try { engine?.dispose() } catch (_: Exception) {}

        engine = null
        synchronized(sentenceList) { sentenceList.clear() }
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        _currentEngine.value = null
        scope?.cancel()
        scope = null
        Napier.i("$TAG: 转写已取消")
    }

    suspend fun switchLanguage(languageCode: String): Result<Unit> {
        val current = engine ?: return Result.failure(IllegalStateException("没有活跃的引擎"))
        try {
            current.finalize()
            val config = AsrConfig(language = languageCode)
            val result = current.start(config)
            if (result.isSuccess) Napier.i("$TAG: 已切换语言: $languageCode")
            return result
        } catch (e: Exception) {
            Napier.e("$TAG: 切换语言失败", e)
            return Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "TranscriptionUseCase"
    }
}
