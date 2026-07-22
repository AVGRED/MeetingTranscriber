package com.example.mt.platform

/**
 * 平台文件路径访问接口（expect 声明）。
 *
 * - Android actual: Context.getFilesDir() / getExternalFilesDir()
 * - Desktop actual: System.getProperty("user.home") / 用户选择的目录
 */
expect class FileAccess() {
    /** 应用私有数据目录（用于存储数据库、配置文件） */
    fun getDataDir(): String

    /** 音频文件存储目录（用于 WAV 录音） */
    fun getAudioDir(): String

    /** 模型文件目录（用于存放 .onnx 等模型文件） */
    fun getModelDir(): String

    /** 导出文件目录（用于生成纪要 TXT 等） */
    fun getExportDir(): String
}
