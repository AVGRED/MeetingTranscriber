package com.example.mt.platform

import android.content.Context
import android.util.Log
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
        val parent = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(parent, "audio")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    actual fun getModelDir(): String {
        // Android 模型从 assets 加载，保留本地路径供模型更新/下载
        val dir = File(ctx.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    actual fun getExportDir(): String {
        val parent = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(parent, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }
}
