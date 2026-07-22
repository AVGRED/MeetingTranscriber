package com.example.mt.platform

import kotlinx.coroutines.flow.SharedFlow

/**
 * 平台音频采集接口（expect 声明）。
 *
 * - Android actual: android.media.AudioRecord
 * - Desktop actual: javax.sound.sampled.TargetDataLine (Java Sound)
 */
expect class PlatformAudioCapture() {
    /** 开始采集，返回是否成功 */
    fun start(): Boolean

    /** 停止采集并释放资源 */
    fun stop()

    /** 实时音频流（16kHz / 16bit / mono PCM，每帧 3200 bytes / 100ms） */
    val audioStream: SharedFlow<ByteArray>

    /** 当前平台是否有录音权限 */
    fun hasPermission(): Boolean
}
