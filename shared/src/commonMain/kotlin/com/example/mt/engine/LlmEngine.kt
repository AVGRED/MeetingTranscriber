package com.example.mt.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * LLM 引擎抽象接口（跨平台，无 Android 依赖）。
 */
interface LlmEngine {

    /** 引擎类型标识 */
    val type: LlmEngineType

    // ── 生命周期 ──

    /**
     * 初始化引擎（加载模型 / 验证 API Key）。
     * 多次调用幂等。
     */
    suspend fun initialize(): Result<Unit>

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
