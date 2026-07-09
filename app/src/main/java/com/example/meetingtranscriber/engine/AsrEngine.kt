package com.example.meetingtranscriber.engine

import android.content.Context
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// ═══════════════════════════════════════════════════════════════
// ASR 引擎抽象接口
// ═══════════════════════════════════════════════════════════════

interface AsrEngine {

    /** 引擎类型标识 */
    val type: AsrEngineType

    // ── 生命周期 ──

    /**
     * 初始化引擎（加载模型 / 预检签名）。
     * 多次调用幂等——已就绪则直接返回成功。
     */
    suspend fun initialize(context: Context): Result<Unit>

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
    fun finalize()

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

// ═══════════════════════════════════════════════════════════════
// ASR 引擎类型
// ═══════════════════════════════════════════════════════════════

enum class AsrEngineType(
    val displayName: String,
    val isCloud: Boolean,
    /** 支持的方言列表 */
    val dialectSupport: List<String> = listOf("普通话", "四川话", "粤语")
) {
    FUNASR_CLOUD("FunASR 云端", isCloud = true),
    FUNASR_LOCAL("FunASR 离线", isCloud = false),
    TINGWU_CLOUD("通义听悟", isCloud = true),
    VOLCENGINE_CLOUD("豆包 ASR", isCloud = true, dialectSupport = listOf("普通话"))
}

// ═══════════════════════════════════════════════════════════════
// 数据结构
// ═══════════════════════════════════════════════════════════════

/**
 * ASR 转写配置。
 */
data class AsrConfig(
    /** 源语言代码，如 "cn" */
    val language: String = "cn",
    /** 可选的热词/词汇表 ID */
    val vocabularyId: String? = null
)

/**
 * ASR 返回的一条完整句子。
 */
data class AsrSentence(
    val text: String,
    val sentenceId: Long,
    /** 离线引擎固定为 "speaker_0"，云端返回实际 speakerId */
    val speakerId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val isFinal: Boolean = true
)
