package com.example.mt.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop actual：java.net.InetAddress 可达性检查。
 *
 * 每 5 秒尝试连接 8.8.8.8 判断网络状态。
 * 使用 getByAddress 避免反向 DNS 查询延迟，在 IO 调度器运行以防阻塞 UI。
 */
actual class NetworkMonitor actual constructor() {

    /**
     * 同步检查网络可达性（阻塞约 2 秒）。
     * ⚠️ 调用方应确保在 IO 协程中访问，不要在主线程调用。
     */
    actual val isNetworkAvailable: Boolean
        get() = try {
            val addr = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
            addr.isReachable(2000)
        } catch (_: Exception) {
            false
        }

    /**
     * 每 5 秒轮询一次网络状态，在 IO 调度器运行。
     */
    actual val networkState: Flow<Boolean> = flow {
        while (true) {
            emit(withContext(Dispatchers.IO) { isNetworkAvailable })
            delay(5.seconds)
        }
    }
}
