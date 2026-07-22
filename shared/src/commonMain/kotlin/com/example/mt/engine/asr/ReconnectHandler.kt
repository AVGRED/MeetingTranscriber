package com.example.mt.engine.asr

import com.example.mt.engine.EngineConstants
import io.github.aakira.napier.Napier
import kotlinx.coroutines.*

/**
 * WebSocket 重连处理器 — 消除各 ASR 引擎间的重复重连逻辑。
 *
 * 每个引擎实例创建一个，负责：
 * - 指数退避重连（base=1s, max=15s, attempts≤5）
 * - 重连 CoroutineScope 生命周期管理
 */
class ReconnectHandler(private val tag: String) {

    @Volatile private var attempts = 0
    @Volatile private var cancelled = false
    @Volatile private var scope: CoroutineScope? = null

    val isExhausted: Boolean get() = attempts >= EngineConstants.MAX_RECONNECT_ATTEMPTS || cancelled

    fun start(onReconnect: suspend (Int) -> Unit) {
        if (isExhausted) {
            Napier.e("$tag: 已达最大重连次数，放弃重连")
            return
        }
        scope?.cancel()
        attempts++
        val delayMs = (EngineConstants.RECONNECT_BASE_DELAY_MS * attempts)
            .coerceAtMost(EngineConstants.RECONNECT_MAX_DELAY_MS)
        Napier.i("$tag: 将在 ${delayMs}ms 后尝试第 $attempts 次重连…")
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope?.launch {
            delay(delayMs)
            onReconnect(attempts)
        }
    }

    fun reset() {
        attempts = 0
        cancelled = false
    }

    fun cancel() {
        cancelled = true
        scope?.cancel()
        scope = null
    }

    fun dispose() {
        cancel()
    }
}
