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

class AudioCaptureManager {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_MS = 100
        const val CHUNK_SIZE = SAMPLE_RATE * 2 * CHUNK_MS / 1000  // 3200 bytes

        // 回落格式：TV USB 麦克风通常只支持 48kHz 立体声
        const val FALLBACK_SAMPLE_RATE = 48000
        const val FALLBACK_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
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
    val audioStream: SharedFlow<ByteArray> = _audioStream

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            MeetingApplication.instance, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun start(): Boolean {
        if (isCapturing.get()) return true
        if (!hasPermission()) return false

        needsResampling = false

        // 按优先级尝试 (sampleRate, channelConfig, chunkSize, needsResample)
        val attempts = listOf(
            listOf(SAMPLE_RATE, CHANNEL_CONFIG, CHUNK_SIZE, false),                     // 16kHz mono
            listOf(FALLBACK_SAMPLE_RATE, FALLBACK_CHANNEL_CONFIG, FALLBACK_CHUNK_SIZE, true)  // 48kHz stereo
        )

        var success = false
        for (attempt in attempts) {
            val rate = attempt[0] as Int
            val channel = attempt[1] as Int
            val chunk = attempt[2] as Int

            if (!tryInitAudioRecord(rate, channel, chunk)) continue

            try {
                audioRecord?.startRecording()
            } catch (e: Exception) {
                android.util.Log.w("AudioCaptureManager", "startRecording 异常 rate=$rate: ${e.message}")
                audioRecord?.release()
                audioRecord = null
                continue
            }

            // 检查 startRecording 是否真正成功（部分 HAL 不抛异常但内部失败）
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                needsResampling = attempt[3] as Boolean
                if (needsResampling) {
                    android.util.Log.i("AudioCaptureManager", "使用回落格式: 48kHz stereo → 16kHz mono 重采样")
                }
                success = true
                break
            }

            android.util.Log.w("AudioCaptureManager",
                "startRecording 失败 rate=$rate, recordingState=${audioRecord?.recordingState}")
            audioRecord?.release()
            audioRecord = null
        }

        if (!success) {
            android.util.Log.e("AudioCaptureManager", "所有音频格式均不可用")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        isCapturing.set(true)

        val chunkSize = if (needsResampling) FALLBACK_CHUNK_SIZE else CHUNK_SIZE

        captureJob = scope.launch {
            val buffer = ByteArray(chunkSize)
            while (isCapturing.get()) {
                val read = audioRecord?.read(buffer, 0, chunkSize) ?: break
                if (read > 0) {
                    val rawData = if (read == chunkSize) buffer.copyOf()
                    else buffer.copyOfRange(0, read)

                    val outputData = if (needsResampling) {
                        Resampler.resample48kStereoTo16kMono(rawData)
                    } else {
                        rawData
                    }

                    // 确保输出是 CHUNK_SIZE 长度（3200 bytes, 100ms @ 16kHz mono）
                    if (outputData.size == CHUNK_SIZE) {
                        _audioStream.emit(outputData)
                    } else if (outputData.size < CHUNK_SIZE) {
                        // 尾部不足 100ms，补齐
                        val padded = ByteArray(CHUNK_SIZE)
                        System.arraycopy(outputData, 0, padded, 0, outputData.size)
                        _audioStream.emit(padded)
                    } else {
                        _audioStream.emit(outputData.copyOfRange(0, CHUNK_SIZE))
                    }
                } else if (read < 0) {
                    android.util.Log.w("AudioCaptureManager", "AudioRecord read 返回 $read，停止采集")
                    break
                }
            }
        }

        return true
    }

    /** 尝试以指定格式构建 AudioRecord，成功返回 true */
    private fun tryInitAudioRecord(sampleRate: Int, channelConfig: Int, chunkSize: Int): Boolean {
        try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AUDIO_FORMAT)
            val bufferSize = maxOf(if (minBuf > 0) minBuf else chunkSize * 4, chunkSize * 4)

            audioRecord?.release()
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                android.util.Log.i("AudioCaptureManager",
                    "AudioRecord 初始化成功: ${sampleRate}Hz, ${if (channelConfig == AudioFormat.CHANNEL_IN_MONO) "mono" else "stereo"}")
                return true
            }

            android.util.Log.w("AudioCaptureManager",
                "AudioRecord 初始化失败: state=${audioRecord?.state}, rate=$sampleRate")
            audioRecord?.release()
            audioRecord = null
            return false
        } catch (e: Exception) {
            android.util.Log.w("AudioCaptureManager",
                "AudioRecord 构建异常: rate=$sampleRate, ${e.message}")
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }

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
        needsResampling = false
    }
}
