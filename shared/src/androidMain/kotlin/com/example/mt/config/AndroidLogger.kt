package com.example.mt.config

import android.util.Log
import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel

/**
 * Napier → android.util.Log 桥接。
 *
 * 在 Application.onCreate() 中调用:
 *   initLogger(AndroidLogger())
 */
class AndroidLogger : Antilog() {
    override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
        when (priority) {
            LogLevel.VERBOSE -> Log.v(tag ?: "MT", message ?: "")
            LogLevel.DEBUG -> Log.d(tag ?: "MT", message ?: "")
            LogLevel.INFO -> Log.i(tag ?: "MT", message ?: "")
            LogLevel.WARNING -> Log.w(tag ?: "MT", message ?: "")
            LogLevel.ERROR -> Log.e(tag ?: "MT", message ?: "")
            LogLevel.ASSERT -> Log.wtf(tag ?: "MT", message ?: "")
        }
        throwable?.let { Log.e(tag ?: "MT", throwable) }
    }
}
