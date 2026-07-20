package com.example.meetingtranscriber.engine.asr

import android.util.Log
import com.example.meetingtranscriber.engine.EngineConstants
import kotlinx.coroutines.*

/**
 * WebSocket 重连处理器 — 消除 FunAsrCloudEngine / VolcengineEngine 间的重复重连逻辑。
 *
 * 每个引擎实例创建一个，负责：
 * - 指数退避重连（base=1s, max=15s, attempts≤5）
 * - 重连 CoroutineScope 生命周期管理
 */
class ReconnectHandler(private val tag: String) {

    @Volatile private var attempts = 0
    @Volatile private var scope: CoroutineScope? = null

    val isExhausted: Boolean get() = attempts >= EngineConstants.MAX_RECONNECT_ATTEMPTS

    fun start(onReconnect: suspend (Int) -> Unit) {
        if (isExhausted) {
            Log.e(tag, "已达最大重连次数，放弃重连")
            return
        }
        scope?.cancel()  // 取消上一次重连（如 onClosed/onFailure 连续触发时不重叠）
        attempts++
        val delayMs = (EngineConstants.RECONNECT_BASE_DELAY_MS * attempts)
            .coerceAtMost(EngineConstants.RECONNECT_MAX_DELAY_MS)
        Log.i(tag, "将在 ${delayMs}ms 后尝试第 $attempts 次重连…")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope?.launch {
            delay(delayMs)
            onReconnect(attempts)
        }
    }

    fun reset() {
        attempts = 0
    }

    fun cancel() {
        attempts = EngineConstants.MAX_RECONNECT_ATTEMPTS  // 阻止后续重连
        scope?.cancel()
        scope = null
    }

    fun dispose() {
        cancel()
    }
}
