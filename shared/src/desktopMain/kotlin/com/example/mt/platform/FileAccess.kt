package com.example.mt.platform

import java.io.File

/**
 * Desktop actual：基于 user.home 的文件系统路径。
 *
 * 目录结构：
 *   ~/MeetingTranscriber/
 *     ├── data/     ← 数据库、配置
 *     ├── audio/    ← WAV 录音
 *     ├── models/   ← .onnx 模型文件
 *     └── exports/  ← 纪要导出
 */
actual class FileAccess actual constructor() {

    private val baseDir: File by lazy {
        File(System.getProperty("user.home"), "MeetingTranscriber").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    private fun ensureDir(name: String): String {
        val dir = File(baseDir, name)
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    actual fun getDataDir(): String = ensureDir("data")

    actual fun getAudioDir(): String = ensureDir("audio")

    actual fun getModelDir(): String = ensureDir("models")

    actual fun getExportDir(): String = ensureDir("exports")
}
