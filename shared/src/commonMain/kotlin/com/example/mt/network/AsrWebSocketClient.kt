package com.example.mt.network

import com.example.mt.engine.EngineConstants
import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 通义听悟实时语音转写 WebSocket 客户端（跨平台，无 Android 依赖）。
 */
class AsrWebSocketClient {

    var onInterimResult: ((String) -> Unit)? = null
    var onSentenceResult: ((AsrSentenceResult) -> Unit)? = null
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile private var webSocket: WebSocket? = null
    private var taskId: String? = null
    @Volatile private var reconnectAttempts = 0
    private var shouldReconnect = true
    @Volatile private var connectionState = ConnectionState.DISCONNECTED
    private val audioBuffer = Channel<ByteArray>(EngineConstants.MAX_BUFFER_FRAMES)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun connect(meetingJoinUrl: String, taskId: String) {
        this.taskId = taskId
        this.shouldReconnect = true
        this.reconnectAttempts = 0
        doConnect(meetingJoinUrl)
    }

    private fun doConnect(url: String) {
        updateState(ConnectionState.CONNECTING)

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/octet-stream")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Napier.i("$TAG: WebSocket 连接成功: ${response.message}")
                reconnectAttempts = 0
                updateState(ConnectionState.CONNECTED)
                flushBuffer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Napier.d("$TAG: 收到消息: ${text.take(200)}")
                try { parseMessage(text) } catch (e: Exception) {
                    Napier.w("$TAG: 解析消息失败: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Napier.i("$TAG: WebSocket 关闭中: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Napier.i("$TAG: WebSocket 已关闭: $code $reason")
                if (code != 1000 && shouldReconnect && reconnectAttempts < EngineConstants.MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    updateState(ConnectionState.RECONNECTING)
                    scope.launch {
                        delay(RECONNECT_DELAY_MS * reconnectAttempts)
                        Napier.i("$TAG: 正在重连... (第${reconnectAttempts}次)")
                        doConnect(url)
                    }
                } else {
                    updateState(ConnectionState.DISCONNECTED)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Napier.e("$TAG: WebSocket 连接失败: ${t.message}")
                if (shouldReconnect && reconnectAttempts < EngineConstants.MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    updateState(ConnectionState.RECONNECTING)
                    scope.launch {
                        delay(RECONNECT_DELAY_MS * reconnectAttempts)
                        Napier.i("$TAG: 正在重连... (第${reconnectAttempts}次)")
                        doConnect(url)
                    }
                } else {
                    while (audioBuffer.tryReceive().isSuccess) { /* drain */ }
                    updateState(ConnectionState.FAILED)
                    onError?.invoke("连接失败: ${t.message}")
                }
            }
        })
    }

    fun sendAudio(pcmData: ByteArray) {
        if (connectionState == ConnectionState.CONNECTED) {
            try {
                webSocket?.send(ByteString.of(*pcmData))
            } catch (e: Exception) {
                Napier.w("$TAG: 发送音频失败: ${e.message}")
            }
        } else if (shouldReconnect) {
            audioBuffer.trySend(pcmData.copyOf())
        }
    }

    private fun flushBuffer() {
        var sent = 0
        while (true) {
            val frame = audioBuffer.tryReceive().getOrNull() ?: break
            try {
                webSocket?.send(ByteString.of(*frame))
                sent++
            } catch (e: Exception) {
                Napier.w("$TAG: 缓冲帧发送失败: ${e.message}")
                break
            }
        }
        if (sent > 0) Napier.i("$TAG: 已重发 $sent 帧缓冲音频")
    }

    fun disconnect() {
        shouldReconnect = false
        scope.coroutineContext[Job]?.cancelChildren()
        try { webSocket?.close(1000, "用户结束会议") } catch (_: Exception) {}
        webSocket = null
        while (audioBuffer.tryReceive().isSuccess) { /* drain */ }
        updateState(ConnectionState.DISCONNECTED)
    }

    /**
     * 缓冲中待发送的帧数（近似值）。
     * Channel 不暴露准确 size，此方法返回 -1 表示"不确定"，
     * 仅保留 API 兼容，调用方不应依赖精确值做决策。
     */
    @Deprecated("Channel 不暴露 size，此方法始终返回 -1")
    fun getBufferSize(): Int = -1

    private fun parseMessage(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val header = json.optJSONObject("header") ?: return
        val name = header.optString("name", "")
        val payload = json.optJSONObject("payload") ?: return

        when (name) {
            "TranscriptionStarted" -> Napier.i("$TAG: 转写开始")
            "TranscriptionResultChanged" -> {
                val text = payload.optString("result", "")
                if (text.isNotBlank()) onInterimResult?.invoke(text)
            }
            "SentenceBegin" -> Napier.d("$TAG: 句子开始: ${payload.optString("sentence_id", "")}")
            "SentenceEnd" -> {
                val result = AsrSentenceResult(
                    text = payload.optString("result", ""),
                    sentenceId = payload.optLong("sentence_id", 0),
                    speakerId = payload.optString("speaker_id", "unknown"),
                    startTimeMs = payload.optLong("start_time", 0),
                    endTimeMs = payload.optLong("end_time", 0),
                    isFinal = true,
                )
                if (result.text.isNotBlank()) onSentenceResult?.invoke(result)
            }
            "TranscriptionCompleted" -> Napier.i("$TAG: 转写完成")
            "TaskFailed" -> {
                val errorMsg = payload.optString("error_message", "未知错误")
                Napier.e("$TAG: 转写任务失败: $errorMsg")
                onError?.invoke("转写失败: $errorMsg")
            }
        }
    }

    private fun updateState(state: ConnectionState) {
        connectionState = state
        onConnectionStateChanged?.invoke(state)
    }

    companion object {
        private const val TAG = "AsrWebSocketClient"
        private const val RECONNECT_DELAY_MS = 2000L
    }
}
