package com.example.mt.config

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel

/**
 * Napier → println 桥接（Desktop）。
 *
 * 在 Main.kt 入口调用:
 *   initLogger(DesktopLogger())
 *
 * Phase 2 可替换为 SLF4J 桥接。
 */
class DesktopLogger : Antilog() {
    override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
        val prefix = when (priority) {
            LogLevel.VERBOSE -> "V"
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARNING -> "W"
            LogLevel.ERROR -> "E"
            LogLevel.ASSERT -> "A"
        }
        println("$prefix/${tag ?: "MT"}: ${message ?: ""}")
        throwable?.printStackTrace()
    }
}
