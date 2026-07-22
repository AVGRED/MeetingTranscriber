package com.example.mt.network

import com.example.mt.engine.AsrSentence

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

// AsrSentenceResult 已合并到 engine.AsrSentence（字段完全兼容，且多了 isContinuation）
typealias AsrSentenceResult = AsrSentence
