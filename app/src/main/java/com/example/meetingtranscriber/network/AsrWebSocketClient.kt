package com.example.meetingtranscriber.network

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 通义听悟实时语音转写 WebSocket 客户端
 *
 * 1. 先通过 REST API 创建转写任务，获取 MeetingJoinUrl
 * 2. 使用 MeetingJoinUrl 建立 WebSocket 连接
 * 3. 发送二进制 PCM 音频帧（16kHz/16bit/mono）
 * 4. 实时接收 JSON 格式的转写结果
 */
class AsrWebSocketClient {

    companion object {
        private const val TAG = "AsrWebSocketClient"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2000L
    }

    var onInterimResult: ((String) -> Unit)? = null
    var onSentenceResult: ((AsrSentenceResult) -> Unit)? = null
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED
    }

    private var webSocket: WebSocket? = null
    private var taskId: String? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    @Volatile private var connectionState = ConnectionState.DISCONNECTED

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
                Log.i(TAG, "WebSocket 连接成功: ${response.message}")
                reconnectAttempts = 0
                updateState(ConnectionState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "收到消息: ${text.take(200)}")
                try {
                    parseMessage(text)
                } catch (e: Exception) {
                    Log.w(TAG, "解析消息失败: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 关闭中: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 已关闭: $code $reason")
                updateState(ConnectionState.DISCONNECTED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 连接失败: ${t.message}")
                if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    updateState(ConnectionState.RECONNECTING)
                    scope.launch {
                        delay(RECONNECT_DELAY_MS * reconnectAttempts)
                        Log.i(TAG, "正在重连... (第${reconnectAttempts}次)")
                        doConnect(url)
                    }
                } else {
                    updateState(ConnectionState.FAILED)
                    onError?.invoke("连接失败: ${t.message}")
                }
            }
        })
    }

    fun sendAudio(pcmData: ByteArray) {
        if (connectionState != ConnectionState.CONNECTED) return
        try {
            webSocket?.send(ByteString.of(*pcmData))
        } catch (e: Exception) {
            Log.w(TAG, "发送音频失败: ${e.message}")
        }
    }

    fun disconnect() {
        shouldReconnect = false
        scope.coroutineContext.cancelChildren()
        try {
            webSocket?.close(1000, "用户结束会议")
        } catch (_: Exception) {}
        webSocket = null
        updateState(ConnectionState.DISCONNECTED)
    }

    private fun parseMessage(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val header = json.optJSONObject("header") ?: return
        val name = header.optString("name", "")
        val payload = json.optJSONObject("payload") ?: return

        when (name) {
            "TranscriptionStarted" -> Log.i(TAG, "转写开始")

            "TranscriptionResultChanged" -> {
                val text = payload.optString("result", "")
                if (text.isNotBlank()) {
                    onInterimResult?.invoke(text)
                }
            }

            "SentenceBegin" -> {
                Log.d(TAG, "句子开始: ${payload.optString("sentence_id", "")}")
            }

            "SentenceEnd" -> {
                val result = AsrSentenceResult(
                    text = payload.optString("result", ""),
                    sentenceId = payload.optLong("sentence_id", 0),
                    speakerId = payload.optString("speaker_id", "unknown"),
                    startTimeMs = payload.optLong("start_time", 0),
                    endTimeMs = payload.optLong("end_time", 0),
                    isFinal = true
                )
                if (result.text.isNotBlank()) {
                    onSentenceResult?.invoke(result)
                }
            }

            "TranscriptionCompleted" -> Log.i(TAG, "转写完成")

            "TaskFailed" -> {
                val errorMsg = payload.optString("error_message", "未知错误")
                Log.e(TAG, "转写任务失败: $errorMsg")
                onError?.invoke("转写失败: $errorMsg")
            }
        }
    }

    private fun updateState(state: ConnectionState) {
        connectionState = state
        onConnectionStateChanged?.invoke(state)
    }
}

data class AsrSentenceResult(
    val text: String,
    val sentenceId: Long,
    val speakerId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val isFinal: Boolean = true
)
