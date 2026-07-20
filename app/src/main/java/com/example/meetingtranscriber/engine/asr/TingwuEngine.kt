package com.example.meetingtranscriber.engine.asr

import android.content.Context
import android.util.Log
import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.engine.*
import com.example.meetingtranscriber.network.AsrWebSocketClient
import com.example.meetingtranscriber.network.TingwuApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 通义听悟云端 ASR 引擎。
 *
 * 通过 REST API 创建实时转写任务 → WebSocket 推送 PCM 音频 →
 * 实时接收 JSON 转写结果。
 *
 * 密钥来源: [PreferencesManager] (EncryptedSharedPrefs)，运行时用户输入。
 */
class TingwuEngine(
    private val prefs: PreferencesManager
) : AsrEngine {

    override val type: AsrEngineType = AsrEngineType.TINGWU_CLOUD

    private val tingwuApi = TingwuApiClient()
    private val wsClient = AsrWebSocketClient()

    private val _interimText = MutableStateFlow("")
    override val interimText: StateFlow<String> = _interimText

    private val _sentenceResults = MutableSharedFlow<AsrSentence>(extraBufferCapacity = 64)
    override val sentenceResults: SharedFlow<AsrSentence> = _sentenceResults

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus

    private var currentTaskId: String = ""

    // ── 回调 → Flow 桥接 ──

    private fun wireCallbacks() {
        wsClient.onInterimResult = { text ->
            _interimText.value = text
        }

        wsClient.onSentenceResult = { result ->
            _sentenceResults.tryEmit(
                AsrSentence(
                    text = result.text,
                    sentenceId = result.sentenceId,
                    speakerId = result.speakerId,
                    startTimeMs = result.startTimeMs,
                    endTimeMs = result.endTimeMs,
                    isFinal = result.isFinal
                )
            )
        }

        wsClient.onConnectionStateChanged = { state ->
            Log.d(TAG, "通义听悟 WebSocket 状态: $state")
            // WebSocket 断开时重置（除非正在运行中）
            if (state == com.example.meetingtranscriber.network.ConnectionState.FAILED) {
                if (_engineStatus.value.state == EngineState.RUNNING) {
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "WebSocket 连接失败")
                }
            }
        }

        wsClient.onError = { msg ->
            Log.e(TAG, "通义听悟错误: $msg")
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // AsrEngine 实现
    // ═══════════════════════════════════════════════════════════

    override suspend fun initialize(context: Context): Result<Unit> {
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return Result.success(Unit)
        }

        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在验证通义听悟密钥...")

        if (!prefs.hasTingwuKeys()) {
            val msg = "通义听悟密钥未配置 (需 AccessKey ID / Secret / AppKey)"
            Log.w(TAG, msg)
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            return Result.failure(IllegalStateException(msg))
        }

        wireCallbacks()
        _engineStatus.value = EngineStatus(EngineState.READY, "通义听悟已就绪")
        Log.i(TAG, "通义听悟引擎初始化成功")
        return Result.success(Unit)
    }

    override suspend fun start(config: AsrConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在创建通义听悟任务...")

            val taskResult = tingwuApi.createRealtimeTask(
                appKey = prefs.tingwuAppKey,
                accessKeyId = prefs.tingwuAccessKeyId,
                accessKeySecret = prefs.tingwuAccessKeySecret,
                sourceLanguage = config.language,
                vocabularyId = config.vocabularyId
            )

            if (taskResult == null || !taskResult.isValid) {
                val msg = "创建通义听悟实时转写任务失败"
                Log.e(TAG, msg)
                _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
                return@withContext Result.failure(IOException(msg))
            }

            currentTaskId = taskResult.taskId
            Log.i(TAG, "通义听悟任务已创建: $currentTaskId")

            // WebSocket 连接是同步的（在 OkHttp 线程池中异步建立）
            wsClient.connect(taskResult.meetingJoinUrl, taskResult.taskId)
            _interimText.value = ""
            _engineStatus.value = EngineStatus(EngineState.RUNNING)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "启动通义听悟失败", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "启动失败: ${e.message}")
            Result.failure(e)
        }
    }

    override fun processAudio(pcmData: ByteArray) {
        if (_engineStatus.value.state != EngineState.RUNNING) return
        wsClient.sendAudio(pcmData)
    }

    override suspend fun finalize() {
        try {
            wsClient.disconnect()
            Log.i(TAG, "通义听悟 WebSocket 已断开")
        } catch (e: Exception) {
            Log.e(TAG, "finalize 异常", e)
        }
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.READY, "转写已结束")
    }

    override suspend fun dispose() {
        try {
            wsClient.disconnect()
        } catch (_: Exception) {}
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        currentTaskId = ""
        Log.i(TAG, "通义听悟引擎已释放")
    }

    companion object {
        private const val TAG = "TingwuEngine"
    }
}
