package com.example.mt.platform

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.mt.audio.Resampler
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android actual 实现：android.media.AudioRecord。
 *
 * 桥接回原 AudioCaptureManager 逻辑。
 * 若升级为独立 KMP 项目可完全复用此文件。
 */
actual class PlatformAudioCapture actual constructor() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_MS = 100
        const val CHUNK_SIZE = SAMPLE_RATE * 2 * CHUNK_MS / 1000  // 3200 bytes

        const val FALLBACK_SAMPLE_RATE = 48000
        const val FALLBACK_CHUNK_SIZE = FALLBACK_SAMPLE_RATE * 2 * 2 * CHUNK_MS / 1000  // 19200 bytes
    }

    private var audioRecord: AudioRecord? = null
    private val isCapturing = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var needsResampling: Boolean = false

    private val _audioStream = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    actual val audioStream: SharedFlow<ByteArray> = _audioStream

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    actual fun hasPermission(): Boolean {
        val ctx = getAppContext() ?: return false
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    actual fun start(): Boolean {
        if (isCapturing.get()) return true
        if (!hasPermission()) return false

        needsResampling = false

        val attempts = listOf(
            listOf(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, CHUNK_SIZE, false),
            listOf(FALLBACK_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, FALLBACK_CHUNK_SIZE, true)
        )

        for (attempt in attempts) {
            val rate = attempt[0] as Int
            val channel = attempt[1] as Int
            val chunk = attempt[2] as Int

            if (!tryInitAudioRecord(rate, channel, chunk)) continue

            try {
                audioRecord?.startRecording()
            } catch (e: Exception) {
                audioRecord?.release()
                audioRecord = null
                continue
            }

            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                needsResampling = attempt[3] as Boolean
                break
            }
            audioRecord?.release()
            audioRecord = null
        }

        if (audioRecord == null) return false

        isCapturing.set(true)
        val chunkSize = if (needsResampling) FALLBACK_CHUNK_SIZE else CHUNK_SIZE

        captureJob = scope.launch {
            val buffer = ByteArray(chunkSize)
            while (isCapturing.get()) {
                val read = audioRecord?.read(buffer, 0, chunkSize) ?: break
                if (read > 0) {
                    val raw = if (read == chunkSize) buffer.copyOf() else buffer.copyOfRange(0, read)
                    val output = if (needsResampling) Resampler.resample48kStereoTo16kMono(raw) else raw
                    if (output.size == CHUNK_SIZE) _audioStream.emit(output)
                    else if (output.size < CHUNK_SIZE) {
                        val padded = ByteArray(CHUNK_SIZE)
                        System.arraycopy(output, 0, padded, 0, output.size)
                        _audioStream.emit(padded)
                    } else {
                        _audioStream.emit(output.copyOfRange(0, CHUNK_SIZE))
                    }
                } else if (read < 0) break
            }
        }
        return true
    }

    actual fun stop() {
        isCapturing.set(false)
        captureJob?.cancel()
        captureJob = null
        scope.coroutineContext[Job]?.cancelChildren()
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        needsResampling = false
    }

    private fun tryInitAudioRecord(sampleRate: Int, channelConfig: Int, chunkSize: Int): Boolean {
        try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(if (minBuf > 0) minBuf else chunkSize * 4, chunkSize * 4)
            audioRecord?.release()
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setBufferSizeInBytes(bufSize)
                .build()
            return audioRecord?.state == AudioRecord.STATE_INITIALIZED
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }
}
