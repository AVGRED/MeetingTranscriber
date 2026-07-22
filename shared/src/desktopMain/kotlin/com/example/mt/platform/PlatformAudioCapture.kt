package com.example.mt.platform

import com.example.mt.audio.Resampler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

actual class PlatformAudioCapture actual constructor() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_MS = 100
        const val CHUNK_SIZE = SAMPLE_RATE * 2 * CHUNK_MS / 1000 // 3200 bytes

        const val FALLBACK_SAMPLE_RATE = 48000
        const val FALLBACK_CHUNK_SIZE = FALLBACK_SAMPLE_RATE * 2 * 2 * CHUNK_MS / 1000 // 19200 bytes
    }

    private var targetLine: TargetDataLine? = null
    private val isCapturing = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var needsResampling = false

    private val _audioStream = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    actual val audioStream: SharedFlow<ByteArray> = _audioStream

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    actual fun hasPermission(): Boolean = true

    actual fun start(): Boolean {
        if (isCapturing.get()) return true

        needsResampling = false

        val attempts = listOf(
            Triple(SAMPLE_RATE.toFloat(), 1, CHUNK_SIZE) to false,
            Triple(FALLBACK_SAMPLE_RATE.toFloat(), 2, FALLBACK_CHUNK_SIZE) to true
        )

        for ((config, needResample) in attempts) {
            val (rate, channels, chunk) = config
            if (!tryInitTargetLine(rate, channels, chunk)) continue

            try {
                targetLine?.start()
            } catch (e: Exception) {
                targetLine?.close()
                targetLine = null
                continue
            }

            if (targetLine?.isRunning == true) {
                needsResampling = needResample
                break
            }
            targetLine?.close()
            targetLine = null
        }

        if (targetLine == null) return false

        isCapturing.set(true)
        val chunkSize = if (needsResampling) FALLBACK_CHUNK_SIZE else CHUNK_SIZE

        captureJob = scope.launch {
            val buffer = ByteArray(chunkSize)
            while (isCapturing.get()) {
                val read = targetLine?.read(buffer, 0, chunkSize) ?: break
                if (read > 0) {
                    val raw = if (read == chunkSize) buffer.copyOf() else buffer.copyOfRange(0, read)
                    val output = if (needsResampling) Resampler.resample48kStereoTo16kMono(raw) else raw
                    when {
                        output.size == CHUNK_SIZE -> _audioStream.emit(output)
                        output.size < CHUNK_SIZE -> {
                            val padded = ByteArray(CHUNK_SIZE)
                            System.arraycopy(output, 0, padded, 0, output.size)
                            _audioStream.emit(padded)
                        }
                        else -> _audioStream.emit(output.copyOfRange(0, CHUNK_SIZE))
                    }
                } else if (read < 0) break
                // read == 0 是合法的（line 暂无可读数据），短暂 yield 后重试
                else if (read == 0) {
                    kotlinx.coroutines.yield()
                }
            }
        }
        return true
    }

    actual fun stop() {
        isCapturing.set(false)
        captureJob?.cancel()
        captureJob = null
        scope.coroutineContext[Job]?.cancelChildren()
        try { targetLine?.stop(); targetLine?.close() } catch (_: Exception) {}
        targetLine = null
        needsResampling = false
    }

    private fun tryInitTargetLine(sampleRate: Float, channels: Int, chunkSize: Int): Boolean {
        return try {
            val format = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false // little-endian (Windows/Linux x86 native)
            )
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) return false
            val line = AudioSystem.getTargetDataLine(format)
            line.open(format, chunkSize * 4)
            targetLine?.close()
            targetLine = line
            true
        } catch (e: Exception) {
            targetLine?.close()
            targetLine = null
            false
        }
    }
}
