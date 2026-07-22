package com.example.mt.audio

/**
 * 平台 WAV 播放器（expect 声明）。
 *
 * Desktop actual: AudioSystem.getSourceDataLine() 回放。
 * Android actual: AudioTrack 回放。
 */
expect class WavPlayer() {
    /** 是否正在播放 */
    val isPlaying: Boolean

    /** 开始播放 WAV 文件，返回是否成功 */
    fun play(filePath: String): Boolean

    /** 停止播放 */
    fun stop()
}
