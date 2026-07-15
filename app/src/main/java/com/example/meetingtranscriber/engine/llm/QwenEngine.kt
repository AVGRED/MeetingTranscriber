package com.example.meetingtranscriber.engine.llm

import android.content.Context
import android.util.Log
import com.example.meetingtranscriber.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Qwen-2-1.5B 本地 LLM 引擎（默认纪要引擎）。
 *
 * 通过 llama.cpp JNI 在设备上运行 Qwen-2-1.5B GGUF Q4_K_M，
 * 无需网络。模型文件 ~1.1GB，由 [ModelDownloadManager] 首次启动下载。
 *
 * 技术参数:
 * - 模型: Qwen-2-1.5B-Chat GGUF Q4_K_M (~1.1GB)
 * - 推理框架: llama.cpp JNI
 * - 速度: ~12-15 tok/s (骁龙 8 Gen2)
 * - 内存: ~2.5GB RAM (包含模型+推理)
 * - 上下文: 2048 tokens
 *
 * ### JNI 桥接
 * 所有 native 调用通过 [LlmNative] 对象，方便替换实现或 mock 测试。
 * 首次部署需要确保:
 * 1. `CMakeLists.txt` 中链接 llama.cpp
 * 2. `.so` 文件包含 arm64-v8a / armeabi-v7a 架构
 * 3. `LlmNative.kt` 中的 native 方法声明与 C++ 实现匹配
 */
class QwenEngine(
    private val context: Context
) : LlmEngine {

    override val type: LlmEngineType = LlmEngineType.QWEN_LOCAL

    private val _generationProgress = MutableStateFlow(0f)
    override val generationProgress: StateFlow<Float> = _generationProgress

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus

    @Volatile private var modelLoaded = false
    private var modelPath: String = ""

    // ═══════════════════════════════════════════════════════════
    // LlmEngine 实现
    // ═══════════════════════════════════════════════════════════

    override suspend fun initialize(context: Context): Result<Unit> =
        withContext(Dispatchers.IO) {
            val current = _engineStatus.value
            if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
                return@withContext Result.success(Unit)
            }

            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在加载 Qwen-1.8B 模型...")

            try {
                // 确定模型文件路径
                val modelDir = java.io.File(context.filesDir, MODEL_DIR)
                val modelFile = java.io.File(modelDir, MODEL_FILE_NAME)

                if (!modelFile.exists()) {
                    val msg = "Qwen 模型文件不存在，请先通过设置页面下载 (${modelFile.absolutePath})"
                    Log.w(TAG, msg)
                    _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
                    return@withContext Result.failure(java.io.FileNotFoundException(msg))
                }

                modelPath = modelFile.absolutePath
                val modelSizeMB = modelFile.length() / (1024 * 1024)
                Log.i(TAG, "模型大小: ${modelSizeMB}MB, 路径: $modelPath")

                // ── 加载模型到 llama.cpp ──
                _engineStatus.value = EngineStatus(EngineState.LOADING, "正在加载模型...")
                _generationProgress.value = 0.3f

                val ok = LlmNative.loadModel(
                    modelPath = modelPath,
                    nCtx = N_CTX,           // 上下文窗口 2048
                    nThreads = N_THREADS,    // 线程数
                    useMmap = true,          // 内存映射加速加载
                    useMlock = false
                )

                if (!ok) {
                    val msg = "模型加载失败: llama.cpp 返回 false（可能内存不足或模型损坏）"
                    Log.e(TAG, msg)
                    _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
                    return@withContext Result.failure(RuntimeException(msg))
                }

                modelLoaded = true
                _generationProgress.value = 1f
                _engineStatus.value = EngineStatus(EngineState.READY, "Qwen-1.8B 已就绪")
                Log.i(TAG, "Qwen 引擎初始化成功")
                Result.success(Unit)
            } catch (e: LinkageError) {
                // UnsatisfiedLinkError / init 块失败的 ExceptionInInitializerError /
                // 二次触碰的 NoClassDefFoundError 都是 LinkageError 子类
                val msg = "llama.cpp native 库未找到，请确保 .so 已正确编译"
                Log.e(TAG, msg, e)
                _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
                Result.failure(RuntimeException(msg, e))
            } catch (e: Exception) {
                Log.e(TAG, "Qwen 引擎初始化失败", e)
                _engineStatus.value = EngineStatus(EngineState.ERROR, "初始化失败: ${e.message}")
                Result.failure(e)
            }
        }

    override suspend fun generateSummary(
        transcript: String,
        style: SummaryStyle
    ): Result<String> = withContext(Dispatchers.IO) {
        if (transcript.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("转写内容为空"))
        }
        if (_engineStatus.value.state != EngineState.READY) {
            return@withContext Result.failure(IllegalStateException("引擎未就绪，当前状态: ${_engineStatus.value.state}"))
        }
        if (!modelLoaded) {
            return@withContext Result.failure(IllegalStateException("模型未加载，请先调用 initialize()"))
        }

        _engineStatus.value = EngineStatus(EngineState.RUNNING)
        _generationProgress.value = 0f

        try {
            val prompt = buildQwenPrompt(transcript, style)
            Log.d(TAG, "Prompt 长度: ${prompt.length} chars")

            // ── llama.cpp 推理 ──
            val result = LlmNative.generate(
                prompt = prompt,
                maxTokens = MAX_TOKENS,
                temperature = 0.3f,
                topP = 0.9f,
                topK = 40,
                repeatPenalty = 1.1f,
                stopStrings = arrayOf("<|im_end|>", "<|endoftext|>"),
                callback = { tokenCount ->
                    // 进度回调（粗略：按 token 数量 / maxTokens 估算）
                    val progress = (tokenCount.toFloat() / MAX_TOKENS).coerceAtMost(0.95f)
                    _generationProgress.value = progress
                }
            )

            _generationProgress.value = 1f
            _engineStatus.value = EngineStatus(EngineState.READY, "纪要已生成")

            if (result.isNotBlank()) {
                Log.i(TAG, "Qwen 纪要生成成功 (${result.length} 字)")
                Result.success(result.trim())
            } else {
                Log.w(TAG, "Qwen 返回空内容")
                Result.failure(RuntimeException("Qwen 返回空内容"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Qwen 推理失败", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "推理失败: ${e.message}")
            Result.failure(e)
        } finally {
            if (_engineStatus.value.state == EngineState.RUNNING) {
                _generationProgress.value = 0f
            }
        }
    }

    override fun cancel() {
        LlmNative.cancelGenerate()
        Log.i(TAG, "已请求取消生成")
    }

    override suspend fun dispose() {
        cancel()
        withContext(Dispatchers.IO) {
            try {
                if (modelLoaded) {
                    LlmNative.unloadModel()
                    modelLoaded = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "dispose 异常", e)
            }
            _generationProgress.value = 0f
            _engineStatus.value = EngineStatus(EngineState.IDLE)
            modelPath = ""
            Log.i(TAG, "Qwen 引擎已释放")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Qwen Chat Template Prompt 构建
    // ═══════════════════════════════════════════════════════════

    /**
     * 构建 Qwen-2 标准 Chat Template 格式的 prompt。
     *
     * 格式:
     * ```
     * <|im_start|>system
     * {system}<|im_end|>
     * <|im_start|>user
     * {user_message}<|im_end|>
     * <|im_start|>assistant
     * ```
     */
    private fun buildQwenPrompt(transcript: String, style: SummaryStyle): String {
        val systemPrompt = "你是一位专业的会议纪要助手。你输出清晰、结构化、可执行的会议纪要。"

        val userMessage = when (style) {
            SummaryStyle.STANDARD -> """
请根据以下会议转写内容，生成一份结构化的会议纪要。

要求：
1. 用一段话概述本次会议的主题和目的。
2. 列出 3-5 条主要讨论要点，每条用一句话概括。
3. 如果有明确的决议或待办事项，请单独列出。

会议转写内容：
$transcript
            """.trimIndent()

            SummaryStyle.BULLET -> """
请根据以下会议转写内容，以要点列表形式呈现。

要求：
1. 用 "· " 开头的简洁要点，每点一句话说清。
2. 分为「主题」「讨论」「决议」三个小节。
3. 每小节 3-5 条要点。

会议转写内容：
$transcript
            """.trimIndent()

            SummaryStyle.DECISION_FOCUSED -> """
请根据以下会议转写内容，重点突出决策和行动项。

要求：
1. 首先列出所有决策（编号列表），每条附简要背景（1-2 句）。
2. 然后列出待办事项，标注可推断的负责人和截止时间。
3. 最后附 2-3 句会议概述。

会议转写内容：
$transcript
            """.trimIndent()
        }

        return buildString {
            append("<|im_start|>system\n")
            append(systemPrompt)
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            append(userMessage)
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    companion object {
        private const val TAG = "QwenEngine"

        // 模型
        const val MODEL_DIR = "models"
        const val MODEL_FILE_NAME = "qwen2-1.5b-chat-q4_k_m.gguf"

        // 推理参数
        private const val N_CTX = 2048
        private const val N_THREADS = 4        // 4 核推理，平衡性能与功耗
        private const val MAX_TOKENS = 1000
    }
}
