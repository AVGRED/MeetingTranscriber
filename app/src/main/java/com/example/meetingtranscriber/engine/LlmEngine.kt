package com.example.meetingtranscriber.engine

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

// ═══════════════════════════════════════════════════════════════
// LLM 引擎抽象接口
// ═══════════════════════════════════════════════════════════════

interface LlmEngine {

    /** 引擎类型标识 */
    val type: LlmEngineType

    // ── 生命周期 ──

    /**
     * 初始化引擎（加载模型 / 验证 API Key）。
     * 多次调用幂等。
     */
    suspend fun initialize(context: Context): Result<Unit>

    /**
     * 生成会议纪要。
     * @param transcript 完整转写文本
     * @param style 纪要风格
     */
    suspend fun generateSummary(transcript: String, style: SummaryStyle): Result<String>

    /**
     * 取消正在进行的生成操作。
     * 如果未在生成中，调用无副作用。
     */
    fun cancel()

    /** 释放资源 */
    suspend fun dispose()

    // ── 状态 ──

    /** 生成进度 0f → 1f */
    val generationProgress: StateFlow<Float>

    /** 引擎状态 */
    val engineStatus: StateFlow<EngineStatus>
}

// ═══════════════════════════════════════════════════════════════
// LLM 引擎类型
// ═══════════════════════════════════════════════════════════════

enum class LlmEngineType(val displayName: String, val isCloud: Boolean) {
    QWEN_LOCAL("Qwen-2-1.5B 本地", isCloud = false),
    DOUBAO_CLOUD("豆包 (火山方舟)", isCloud = true),
    DASHSCOPE_CLOUD("通义千问 (DashScope)", isCloud = true),
    DEEPSEEK_CLOUD("DeepSeek", isCloud = true),
    KIMI_CLOUD("Kimi (月之暗面)", isCloud = true),
    ZHIPU_CLOUD("智谱 GLM", isCloud = true),
    SILICONFLOW_CLOUD("硅基流动 (聚合)", isCloud = true)
}

// ═══════════════════════════════════════════════════════════════
// 纪要风格
// ═══════════════════════════════════════════════════════════════

enum class SummaryStyle(val label: String) {
    STANDARD("标准纪要"),
    BULLET("要点列表"),
    DECISION_FOCUSED("决策重点")
}
