package com.example.mt.engine

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ASR 引擎抽象接口（跨平台，无 Android 依赖）。
 *
 * 引擎由外部注入密钥配置后自行管理连接，不再需要 Context 参数。
 */
interface AsrEngine {

    /** 引擎类型标识 */
    val type: AsrEngineType

    // ── 生命周期 ──

    /**
     * 初始化引擎（加载模型 / 预检签名）。
     * 多次调用幂等——已就绪则直接返回成功。
     */
    suspend fun initialize(): Result<Unit>

    /**
     * 启动转写会话（建立 WebSocket 或创建识别流）。
     */
    suspend fun start(config: AsrConfig): Result<Unit>

    // ── 音频处理 ──

    /**
     * 喂入一帧 PCM 音频数据（16kHz / 16bit / mono）。
     * 引擎内部自行处理 VAD 和流式推送到后端。
     */
    fun processAudio(pcmData: ByteArray)

    /**
     * 通知引擎音频输入结束，冲刷缓冲区以获取最后的结果。
     */
    suspend fun finalize()

    /**
     * 释放引擎占用的所有资源（WebSocket / native 内存 / 流）。
     */
    suspend fun dispose()

    // ── 实时输出 ──

    /** 逐词中间结果（实时出字），句子未结束时持续更新 */
    val interimText: StateFlow<String>

    /** 完整句子流，一句一个事件 */
    val sentenceResults: SharedFlow<AsrSentence>

    /** 引擎状态 */
    val engineStatus: StateFlow<EngineStatus>
}
