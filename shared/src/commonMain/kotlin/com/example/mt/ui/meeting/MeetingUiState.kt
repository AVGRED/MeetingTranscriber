package com.example.mt.ui.meeting

import com.example.mt.engine.EngineState
import com.example.mt.network.ConnectionState

/**
 * 会议界面的完整 UI 状态（跨平台）。
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
    val asrEngineName: String = "",
    val asrEngineStatus: EngineState = EngineState.IDLE,
    val isGeneratingSummary: Boolean = false,
    val summaryProgress: Float = 0f,
    val showSummaryReviewDialog: Boolean = false,
    val latestSummary: String = "",
    val summaryMeetingId: Long = 0L,
)
