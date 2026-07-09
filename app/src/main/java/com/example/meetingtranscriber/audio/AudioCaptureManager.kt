package com.example.meetingtranscriber.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.meetingtranscriber.MeetingApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 音频采集管理器
 *
 * 使用 AudioRecord 采集 16kHz/16bit/单声道 PCM 音频
 * 每 100ms 输出一个音频帧，通过 SharedFlow 发送给下游
 */
class AudioCaptureManager {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_MS = 100
        const val CHUNK_SIZE = SAMPLE_RATE * 2 * CHUNK_MS / 1000  // 3200 bytes
    }

    private var audioRecord: AudioRecord? = null
    private val isCapturing = AtomicBoolean(false)
    private var captureJob: Job? = null

    /** 音频数据流，每秒 10 帧 */
    private val _audioStream = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioStream: SharedFlow<ByteArray> = _audioStream

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 是否有录音权限 */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            MeetingApplication.instance, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 开始采集 */
    fun start(): Boolean {
        if (isCapturing.get()) return true
        if (!hasPermission()) return false

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuf, CHUNK_SIZE * 4)

        audioRecord = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return false
        }

        audioRecord?.startRecording()
        isCapturing.set(true)

        captureJob = scope.launch {
            val buffer = ByteArray(CHUNK_SIZE)
            var totalRead = 0
            while (isActive && isCapturing.get()) {
                val read = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: break
                if (read > 0) {
                    if (read == CHUNK_SIZE) {
                        _audioStream.emit(buffer.copyOf())
                    } else {
                        // 部分读取：补零
                        val data = ByteArray(CHUNK_SIZE)
                        System.arraycopy(buffer, 0, data, 0, read)
                        _audioStream.emit(data)
                    }
                    totalRead += read
                } else if (read < 0) {
                    // AudioRecord 错误
                    break
                }
            }
        }

        return true
    }

    /** 停止采集 */
    fun stop() {
        isCapturing.set(false)
        captureJob?.cancel()
        captureJob = null
        scope.coroutineContext[Job]?.cancelChildren()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }
}
