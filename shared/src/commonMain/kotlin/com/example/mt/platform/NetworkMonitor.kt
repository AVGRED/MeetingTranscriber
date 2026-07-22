package com.example.mt.platform

import kotlinx.coroutines.flow.Flow

/**
 * 网络状态监听接口（expect 声明）。
 *
 * - Android actual: ConnectivityManager + callbackFlow
 * - Desktop actual: java.net.InetAddress 可达性检查
 */
expect class NetworkMonitor() {
    /** 当前网络是否可用（同步检查） */
    val isNetworkAvailable: Boolean

    /** 网络状态变化流 */
    val networkState: Flow<Boolean>
}
