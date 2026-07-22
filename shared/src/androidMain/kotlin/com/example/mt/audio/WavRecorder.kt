package com.example.mt.audio

/**
 * Android actual（桩 — 桥接回原 AudioManager.kt 的 EncryptedFile WAV 录制逻辑）。
 *
 * 原项目已有完整实现，此桩仅用于 KMP 编译通过。
 * 集成时替换为：委托给原 android *.audio.WavRecorder。
 */
actual class WavRecorder actual constructor() {

    actual val isRecording: Boolean get() = false

    actual fun start(filePath: String): Boolean {
        error("Android WavRecorder actual not yet bridged — see AudioManager in original project")
    }

    actual fun write(pcmData: ByteArray) {
        error("Android WavRecorder actual not yet bridged — see AudioManager in original project")
    }

    actual fun stop(): Boolean {
        error("Android WavRecorder actual not yet bridged — see AudioManager in original project")
    }
}
