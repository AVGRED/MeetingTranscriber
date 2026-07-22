package com.example.mt.engine.asr

import com.example.mt.config.EngineKeys
import com.example.mt.engine.*
import com.example.mt.network.AsrWebSocketClient
import com.example.mt.network.ConnectionState
import com.example.mt.network.TingwuApiClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/**
 * 通义听悟云端 ASR 引擎（跨平台，无 Android 依赖）。
 *
 * 通过 REST API 创建实时转写任务 → WebSocket 推送 PCM 音频 →
 * 实时接收 JSON 转写结果。
 */
class TingwuEngine(
    private val keys: EngineKeys,
    private val tingwuApi: TingwuApiClient = TingwuApiClient(),
    private val wsClient: AsrWebSocketClient = AsrWebSocketClient(),
) : AsrEngine {

    override val type: AsrEngineType = AsrEngineType.TINGWU_CLOUD

    private val _interimText = MutableStateFlow("")
    override val interimText: StateFlow<String> = _interimText

    private val _sentenceResults = MutableSharedFlow<AsrSentence>(extraBufferCapacity = 64)
    override val sentenceResults: SharedFlow<AsrSentence> = _sentenceResults

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus

    private var currentTaskId: String = ""

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
                    isFinal = result.isFinal,
                )
            )
        }

        wsClient.onConnectionStateChanged = { state ->
            Napier.d("$TAG: 通义听悟 WebSocket 状态: $state")
            if (state == ConnectionState.FAILED) {
                if (_engineStatus.value.state == EngineState.RUNNING) {
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "WebSocket 连接失败")
                }
            }
        }

        wsClient.onError = { msg ->
            Napier.e("$TAG: 通义听悟错误: $msg")
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // AsrEngine 实现
    // ═══════════════════════════════════════════════════════════

    override suspend fun initialize(): Result<Unit> {
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return Result.success(Unit)
        }

        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在验证通义听悟密钥...")

        if (!keys.hasTingwuKeys()) {
            val msg = "通义听悟密钥未配置 (需 AccessKey ID / Secret / AppKey)"
            Napier.w("$TAG: $msg")
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            return Result.failure(IllegalStateException(msg))
        }

        wireCallbacks()
        _engineStatus.value = EngineStatus(EngineState.READY, "通义听悟已就绪")
        Napier.i("$TAG: 通义听悟引擎初始化成功")
        return Result.success(Unit)
    }

    override suspend fun start(config: AsrConfig): Result<Unit> {
        return try {
            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在创建通义听悟任务...")

            val taskResult = tingwuApi.createRealtimeTask(
                appKey = keys.tingwuAppKey,
                accessKeyId = keys.tingwuAccessKeyId,
                accessKeySecret = keys.tingwuAccessKeySecret,
                sourceLanguage = config.language,
                vocabularyId = config.vocabularyId,
            )

            if (taskResult == null || !taskResult.isValid) {
                val msg = "创建通义听悟实时转写任务失败"
                Napier.e("$TAG: $msg")
                _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
                return Result.failure(IOException(msg))
            }

            currentTaskId = taskResult.taskId
            Napier.i("$TAG: 通义听悟任务已创建: $currentTaskId")

            wsClient.connect(taskResult.meetingJoinUrl, taskResult.taskId)
            _interimText.value = ""
            _engineStatus.value = EngineStatus(EngineState.RUNNING)
            Result.success(Unit)
        } catch (e: Exception) {
            Napier.e("$TAG: 启动通义听悟失败", e)
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
            Napier.i("$TAG: 通义听悟 WebSocket 已断开")
        } catch (e: Exception) {
            Napier.e("$TAG: finalize 异常", e)
        }
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.READY, "转写已结束")
    }

    override suspend fun dispose() {
        try { wsClient.disconnect() } catch (_: Exception) {}
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        currentTaskId = ""
        Napier.i("$TAG: 通义听悟引擎已释放")
    }

    companion object {
        private const val TAG = "TingwuEngine"
    }
}
