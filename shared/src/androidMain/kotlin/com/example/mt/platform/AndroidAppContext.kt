package com.example.mt.platform

import android.content.Context

/**
 * Android Application Context 持有者。
 *
 * 由 Application.onCreate() 注入，供所有 platform actual 实现访问系统服务。
 * 使用 @Volatile + 双重检查确保线程安全初始化。
 */
@Volatile
private var _appContext: Context? = null

/** 由 Application.onCreate 调用 */
fun setAppContext(context: Context) {
    _appContext = context.applicationContext
}

/** 获取 Application Context，未注入时返回 null */
fun getAppContext(): Context? = _appContext
