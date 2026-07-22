package com.example.mt.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop actual：java.net.InetAddress 可达性检查。
 *
 * 每 5 秒尝试连接一个公网地址判断网络状态。
 * 相比 Android ConnectivityManager，Desktop 无系统级网络回调，用轮询替代。
 */
actual class NetworkMonitor actual constructor() {

    /**
     * 同步检查网络可达性。
     * ⚠️ 此 getter 阻塞约 2 秒（InetAddress.isReachable timeout），
     *   调用方应确保在 IO 协程中访问，不要在主线程调用。
     */
    actual val isNetworkAvailable: Boolean
        get() = try {
            InetAddress.getByName("8.8.8.8").isReachable(2000)
        } catch (_: Exception) {
            false
        }

    actual val networkState: Flow<Boolean> = flow {
        while (true) {
            emit(isNetworkAvailable)
            delay(5.seconds)
        }
    }
}
