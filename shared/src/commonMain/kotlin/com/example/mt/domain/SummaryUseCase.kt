package com.example.mt.domain

import com.example.mt.engine.*
import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 纪要生成用例 — 封装「引擎解析 → 初始化 → 生成纪要」生命周期（跨平台）。
 */
class SummaryUseCase(
    private val engineRouter: EngineRouter,
) {

    private val _currentEngine = MutableStateFlow<LlmEngineType?>(null)
    val currentEngine: StateFlow<LlmEngineType?> = _currentEngine

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    val engineStatus: StateFlow<EngineStatus> = _engineStatus

    private val _generationProgress = MutableStateFlow(0f)
    val generationProgress: StateFlow<Float> = _generationProgress

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _lastError = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val lastError: SharedFlow<String> = _lastError

    private var engine: LlmEngine? = null
    private var scope: CoroutineScope? = null

    suspend fun initialize(): Result<Unit> {
        try {
            val resolved = engineRouter.resolveLlmEngine()
            engine = resolved
            _currentEngine.value = resolved.type

            val result = resolved.initialize()
            if (result.isSuccess) {
                Napier.i("$TAG: LLM 引擎已就绪: ${resolved.type.displayName}")
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                scope!!.launch {
                    resolved.engineStatus.collect { _engineStatus.value = it }
                }
                scope!!.launch {
                    resolved.generationProgress.collect { _generationProgress.value = it }
                }
            }
            return result
        } catch (e: NoEngineException) {
            Napier.e("$TAG: 无可用 LLM 引擎: ${e.message}")
            return Result.failure(e)
        } catch (e: Exception) {
            Napier.e("$TAG: 初始化异常", e)
            return Result.failure(e)
        }
    }

    suspend fun generate(
        transcript: String,
        style: SummaryStyle = SummaryStyle.STANDARD,
    ): Result<String> {
        if (transcript.isBlank()) {
            return Result.failure(IllegalArgumentException("转写内容为空"))
        }
        if (_isGenerating.value) {
            return Result.failure(IllegalStateException("正在生成中，请等待完成"))
        }

        _isGenerating.value = true
        _generationProgress.value = 0f

        try {
            if (engine == null) {
                val initResult = initialize()
                if (initResult.isFailure) return Result.failure(initResult.exceptionOrNull()!!)
            }

            val currentEngine = engine!!
            Napier.i("$TAG: 开始生成纪要: engine=${currentEngine.type.displayName}, style=${style.label}")

            val result = currentEngine.generateSummary(transcript, style)
            result.onSuccess { summary ->
                Napier.i("$TAG: 纪要生成成功: ${summary.length} 字")
            }.onFailure { e ->
                Napier.e("$TAG: 纪要生成失败: ${e.message}", e)
            }

            return result
        } catch (e: NoEngineException) {
            Napier.e("$TAG: 无可用 LLM 引擎: ${e.message}")
            return Result.failure(e)
        } catch (e: Exception) {
            Napier.e("$TAG: 生成异常", e)
            return Result.failure(e)
        } finally {
            _isGenerating.value = false
            if (_generationProgress.value < 1f) _generationProgress.value = 0f
        }
    }

    fun cancel() {
        engine?.cancel()
    }

    suspend fun dispose() {
        _isGenerating.value = false
        _generationProgress.value = 0f
        try { engine?.dispose() } catch (_: Exception) {}
        engine = null
        _currentEngine.value = null
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        scope?.cancel()
        scope = null
        Napier.i("$TAG: 已释放")
    }

    companion object {
        private const val TAG = "SummaryUseCase"
    }
}
