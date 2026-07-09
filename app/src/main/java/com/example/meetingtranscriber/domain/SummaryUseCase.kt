package com.example.meetingtranscriber.domain

import android.content.Context
import android.util.Log
import com.example.meetingtranscriber.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 纪要生成用例 — 封装「引擎解析 → 初始化 → 生成纪要」生命周期。
 *
 * ```kotlin
 * // 生成纪要
 * val result = useCase.generate(context, transcript, SummaryStyle.STANDARD)
 * result.onSuccess { summary -> showSummary(summary) }
 * result.onFailure { e -> showError(e.message) }
 * ```
 */
class SummaryUseCase(
    private val engineRouter: EngineRouter
) {
    companion object {
        private const val TAG = "SummaryUseCase"
    }

    // ═══════════════════════════════════════════════════════════
    // 公开状态
    // ═══════════════════════════════════════════════════════════

    /** 当前使用的引擎 */
    private val _currentEngine = MutableStateFlow<LlmEngineType?>(null)
    val currentEngine: StateFlow<LlmEngineType?> = _currentEngine

    /** 引擎状态 */
    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    val engineStatus: StateFlow<EngineStatus> = _engineStatus

    /** 生成进度 0→1 */
    private val _generationProgress = MutableStateFlow(0f)
    val generationProgress: StateFlow<Float> = _generationProgress

    /** 是否正在生成 */
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    /** 最后一个错误 */
    private val _lastError = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val lastError: SharedFlow<String> = _lastError

    // ═══════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════

    private var engine: LlmEngine? = null
    private var scope: CoroutineScope? = null

    /**
     * 初始化引擎（可提前调用以预热模型）。
     */
    suspend fun initialize(context: Context): Result<Unit> {
        try {
            val resolved = engineRouter.resolveLlmEngine(context)
            engine = resolved
            _currentEngine.value = resolved.type

            val result = resolved.initialize(context)
            if (result.isSuccess) {
                Log.i(TAG, "LLM 引擎已就绪: ${resolved.type.displayName}")
                // 监听引擎状态
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                scope!!.launch {
                    resolved.engineStatus.collect { _engineStatus.value = it }
                }
                scope!!.launch {
                    resolved.generationProgress.collect { _generationProgress.value = it }
                }
            }
            return result
        } catch (e: NoEngineException) {
            Log.e(TAG, "无可用 LLM 引擎: ${e.message}")
            _lastError.emit("无可用引擎: ${e.message}")
            return Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "初始化异常", e)
            _lastError.emit("初始化异常: ${e.message}")
            return Result.failure(e)
        }
    }

    /**
     * 生成纪要（会自动调用 initialize，已完成则跳过）。
     *
     * @param context Application Context
     * @param transcript 完整转写文本
     * @param style 纪要风格
     * @return 格式化纪要看文本
     */
    suspend fun generate(
        context: Context,
        transcript: String,
        style: SummaryStyle = SummaryStyle.STANDARD
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
            // 1. 解析 + 初始化引擎（如果没有提前初始化）
            if (engine == null) {
                val initResult = initialize(context)
                if (initResult.isFailure) return Result.failure(initResult.exceptionOrNull()!!)
            }

            val currentEngine = engine!!
            Log.i(TAG, "开始生成纪要: engine=${currentEngine.type.displayName}, style=${style.label}, " +
                    "transcript=${transcript.length} chars")

            // 2. 生成
            val result = currentEngine.generateSummary(transcript, style)
            result.onSuccess { summary ->
                Log.i(TAG, "纪要生成成功: ${summary.length} 字")
            }.onFailure { e ->
                Log.e(TAG, "纪要生成失败: ${e.message}", e)
                _lastError.emit("生成失败: ${e.message}")
            }

            return result
        } catch (e: NoEngineException) {
            val msg = "无可用 LLM 引擎: ${e.message}"
            Log.e(TAG, msg)
            _lastError.emit(msg)
            return Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "生成异常", e)
            _lastError.emit("异常: ${e.message}")
            return Result.failure(e)
        } finally {
            _isGenerating.value = false
            if (_generationProgress.value < 1f) {
                _generationProgress.value = 0f
            }
        }
    }

    /**
     * 取消正在进行的生成操作。
     */
    fun cancel() {
        engine?.cancel()
    }

    /**
     * 释放引擎资源。
     */
    suspend fun dispose() {
        _isGenerating.value = false
        _generationProgress.value = 0f
        try {
            engine?.dispose()
        } catch (_: Exception) {}
        engine = null
        _currentEngine.value = null
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        scope?.cancel()
        scope = null
        Log.i(TAG, "已释放")
    }
}
