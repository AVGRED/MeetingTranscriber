package com.example.mt.platform

/**
 * 声纹模型加载器（expect 声明）。
 *
 * Android actual: 从 assets 加载 sherpa-onnx .onnx 模型
 * Desktop actual: 从文件系统加载，通过 JNA/JNI 调用 sherpa-onnx DLL
 *
 * 成员 B 负责实现 desktop 版本（文件系统加载 + Windows DLL）。
 */
expect class VoiceprintModelLoader() {
    /**
     * 加载声纹模型，返回是否成功。
     * 失败时（模型文件不存在 / DLL 加载失败）返回 false，
     * 调用方应降级为轮次递增模式。
     */
    fun load(): Boolean

    /** 是否已加载 */
    val isLoaded: Boolean

    /**
     * 计算一段 PCM 音频的声纹嵌入向量。
     * @param pcm 16kHz/16bit/mono PCM 数据
     * @return 嵌入向量（浮点数组），null 表示音频不足或推理异常
     */
    fun extractEmbedding(pcm: ByteArray): FloatArray?

    /** 释放原生模型资源 */
    fun release()
}
