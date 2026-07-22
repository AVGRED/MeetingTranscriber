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
        val t = tag ?: "MT"
        val msg = message ?: ""
        when (priority) {
            LogLevel.VERBOSE -> Log.v(t, msg, throwable)
            LogLevel.DEBUG -> Log.d(t, msg, throwable)
            LogLevel.INFO -> Log.i(t, msg, throwable)
            LogLevel.WARNING -> Log.w(t, msg, throwable)
            LogLevel.ERROR -> Log.e(t, msg, throwable)
            LogLevel.ASSERT -> Log.wtf(t, msg, throwable)
        }
    }
}
