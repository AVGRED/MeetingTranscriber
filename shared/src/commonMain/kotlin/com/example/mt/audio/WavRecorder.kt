package com.example.mt.audio

/**
 * 平台 WAV 录制器（expect 声明）。
 *
 * Desktop actual: RandomAccessFile 写入，可附加 AES-GCM 加密。
 * Android actual: 桥接回原 EncryptedFile 实现。
 */
expect class WavRecorder() {
    /** 是否正在录制 */
    val isRecording: Boolean

    /** 开始录制，创建文件并写入 WAV 头，返回是否成功 */
    fun start(filePath: String): Boolean

    /** 追加 PCM 数据 (16kHz/16bit/mono) */
    fun write(pcmData: ByteArray)

    /** 停止录制，回填头信息并关闭文件 */
    fun stop(): Boolean
}
