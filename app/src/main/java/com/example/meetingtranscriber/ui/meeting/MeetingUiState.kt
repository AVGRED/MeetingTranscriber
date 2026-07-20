package com.example.meetingtranscriber.ui.meeting

import com.example.meetingtranscriber.engine.EngineState
import com.example.meetingtranscriber.network.ConnectionState

/**
 * 会议界面的完整 UI 状态。
 */
data class MeetingUiState(
    val isMeetingActive: Boolean = false,
    val isPaused: Boolean = false,
    val isConnected: Boolean = false,
    val isSpeaking: Boolean = false,
    val elapsedSeconds: Int = 0,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val interimText: String = "",
    val speakerLabels: Map<String, String> = emptyMap(),
    val speakerCount: Int = 0,
    val errorMessage: String? = null,
    val selectedLanguage: String = "cn",

    // ── 新增：引擎信息 ──
    /** 当前 ASR 引擎名称（如 "FunASR 云端"） */
    val asrEngineName: String = "",
    /** 当前 ASR 引擎状态 */
    val asrEngineStatus: EngineState = EngineState.IDLE,

    // ── 新增：摘要状态 ──
    /** 是否正在生成摘要 */
    val isGeneratingSummary: Boolean = false,
    /** 摘要生成进度 0f..1f */
    val summaryProgress: Float = 0f,
    // ── 纪要审核弹窗 ──
    /** 纪要生成完成后弹出审核弹窗 */
    val showSummaryReviewDialog: Boolean = false,
    /** 弹窗中展示的纪要文本 */
    val latestSummary: String = "",
    /** 纪要对应的会议 ID（供弹窗操作复用） */
    val summaryMeetingId: Long = 0L
)
