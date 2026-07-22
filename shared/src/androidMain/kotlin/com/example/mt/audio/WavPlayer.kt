package com.example.mt.audio

/**
 * Android actual（桩 — 桥接回原 app 的 AudioTrack WAV 回放逻辑）。
 */
actual class WavPlayer actual constructor() {

    actual val isPlaying: Boolean get() = false

    actual fun play(filePath: String): Boolean {
        error("Android WavPlayer actual not yet bridged — see original project")
    }

    actual fun stop() {
        error("Android WavPlayer actual not yet bridged — see original project")
    }
}
