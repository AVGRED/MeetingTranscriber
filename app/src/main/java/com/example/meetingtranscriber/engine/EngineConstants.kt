package com.example.meetingtranscriber.engine

/**
 * 引擎共享常量 — 消除 FunAsrCloudEngine / VolcengineEngine / CloudAsrWsEngine 间的重复定义。
 */
object EngineConstants {
    /** 内存缓冲最大帧数（100ms/帧 × 1200 ≈ 2 分钟） */
    const val MAX_BUFFER_FRAMES = 1200
    /** 单帧 PCM 大小 (16kHz/16bit/mono/100ms) */
    const val AUDIO_FRAME_SIZE = 3200
    /** WebSocket 最大重连次数 */
    const val MAX_RECONNECT_ATTEMPTS = 5
    /** 重连基础延迟（ms），指数退避基数 */
    const val RECONNECT_BASE_DELAY_MS = 1000L
    /** 重连最大延迟（ms），指数退避上限 */
    const val RECONNECT_MAX_DELAY_MS = 15000L
}
