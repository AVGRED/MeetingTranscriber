package com.example.mt.platform

import android.content.Context
import java.io.File

/**
 * Android actual：Context.getFilesDir / getExternalFilesDir。
 */
actual class FileAccess actual constructor() {

    private val ctx: Context get() = getAppContext()
        ?: throw IllegalStateException("Application Context 未注入")

    actual fun getDataDir(): String {
        return ctx.filesDir.absolutePath
    }

    actual fun getAudioDir(): String {
        val dir = File(ctx.getExternalFilesDir(null), "audio")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    actual fun getModelDir(): String {
        // Android 模型从 assets 加载，无需文件系统路径
        val dir = File(ctx.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    actual fun getExportDir(): String {
        val dir = File(ctx.getExternalFilesDir(null), "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }
}
