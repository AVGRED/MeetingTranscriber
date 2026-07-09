package com.example.meetingtranscriber.network

/**
 * WebSocket / 云端连接状态。
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * ASR 返回的完整句子结果（通义听悟/豆包 WebSocket 回调使用）。
 */
data class AsrSentenceResult(
    val text: String,
    val sentenceId: Long,
    val speakerId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val isFinal: Boolean = true
)
