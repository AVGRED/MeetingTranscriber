package com.example.meetingtranscriber.engine.asr

import android.content.Context
import android.util.Log
import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 豆包/火山引擎云端 ASR 引擎。
 *
 * 使用大模型流式 ASR (bigmodel) 协议，通过二进制 WebSocket 帧通信。
 * 密钥来源: [PreferencesManager] (EncryptedSharedPrefs)。
 *
 * 协议帧格式（大端）:
 * ```
 * [version:1][header_size:1][msg_type:1][flags:1][payload_len:4][payload:N]
 * ```
 */
class VolcengineEngine(
    private val prefs: PreferencesManager
) : AsrEngine {

    override val type: AsrEngineType = AsrEngineType.VOLCENGINE_CLOUD

    // ── 内部状态 ──
    private var webSocket: WebSocket? = null
    private var connectId: String = ""
    private var sentenceCounter: Long = 0

    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()
    private var appContext: Context? = null
    private var overflowFile: File? = null
    private var overflowOutputStream: FileOutputStream? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // ── AsrEngine 输出 ──
    private val _interimText = MutableStateFlow("")
    override val interimText: StateFlow<String> = _interimText

    private val _sentenceResults = MutableSharedFlow<AsrSentence>(extraBufferCapacity = 64)
    override val sentenceResults: SharedFlow<AsrSentence> = _sentenceResults

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus

    // ═══════════════════════════════════════════════════════════
    // AsrEngine 实现
    // ═══════════════════════════════════════════════════════════

    override suspend fun initialize(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return@withContext Result.success(Unit)
        }

        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在验证豆包 ASR 密钥...")

        if (!prefs.hasVolcengineKeys()) {
            val msg = "豆包 ASR 密钥未配置 (需 API Key 或 Access Token)"
            Log.w(TAG, msg)
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        _engineStatus.value = EngineStatus(EngineState.READY, "豆包 ASR 已就绪")
        Log.i(TAG, "豆包 ASR 引擎初始化成功")
        Result.success(Unit)
    }

    override suspend fun start(config: AsrConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            connectId = UUID.randomUUID().toString()
            sentenceCounter = 0
            _interimText.value = ""
            reconnectAttempts = 0
            reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            reconnectConfig = config

            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在连接豆包 ASR...")
            openWebSocket(config)

            _engineStatus.value = EngineStatus(EngineState.RUNNING)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "启动豆包 ASR 失败", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "启动失败: ${e.message}")
            Result.failure(e)
        }
    }

    override fun processAudio(pcmData: ByteArray) {
        if (_engineStatus.value.state != EngineState.RUNNING) return

        if (webSocket != null) {
            // WebSocket 已连接 → 直接发送
            try {
                val frame = buildFrame(MSG_AUDIO_ONLY, FLAG_RAW, pcmData)
                webSocket?.send(ByteString.of(*frame))
            } catch (e: Exception) {
                Log.w(TAG, "发送音频帧失败: ${e.message}")
            }
        } else {
            // 未连接 → 缓冲（先内存，满了落盘）
            if (audioBuffer.size < MAX_BUFFER_SIZE) {
                audioBuffer.offer(pcmData.copyOf())
            } else {
                writeToOverflowFile(pcmData)
            }
        }
    }

    override fun finalize() {
        try {
            sendAudioLastFrame()
            webSocket?.close(1000, "用户结束会议")
        } catch (_: Exception) {}
        webSocket = null
        audioBuffer.clear()
        cleanupOverflowFile()
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.READY, "转写已结束")
        Log.i(TAG, "豆包 ASR finalize 完成")
    }

    override suspend fun dispose() {
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS // 阻止重连
        reconnectConfig = null
        reconnectScope?.cancel()
        reconnectScope = null
        try {
            webSocket?.close(1000, "引擎释放")
        } catch (_: Exception) {}
        webSocket = null
        audioBuffer.clear()
        cleanupOverflowFile()
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        Log.i(TAG, "豆包 ASR 引擎已释放")
    }

    // ═══════════════════════════════════════════════════════════
    // WebSocket 管理 + 重连
    // ═══════════════════════════════════════════════════════════

    @Volatile private var reconnectAttempts = 0
    private var reconnectScope: CoroutineScope? = null
    private var reconnectConfig: AsrConfig? = null

    private fun openWebSocket(config: AsrConfig) {
        val languageCode = if (config.language == "cn") "zh-CN" else config.language
        val requestId = UUID.randomUUID().toString()
        val resourceId = RESOURCE_ID

        val request = Request.Builder()
            .url(WS_URL)
            .apply {
                val apiKey = prefs.volcengineAsrApiKey
                val accessToken = prefs.volcengineAsrAccessToken

                if (apiKey.isNotBlank()) {
                    header("X-Api-Key", apiKey)
                } else if (accessToken.isNotBlank()) {
                    header("X-Api-Access-Key", accessToken)
                }
            }
            .header("X-Api-Resource-Id", resourceId)
            .header("X-Api-Request-Id", requestId)
            .header("X-Api-Connect-Id", connectId)
            .header("X-Api-Sequence", "-1")
            .build()

        Log.i(TAG, "打开 WebSocket: resource=$resourceId, requestId=$requestId")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接, status=${response.code}")
                reconnectAttempts = 0
                sendFullClientRequest(languageCode)
                flushBuffer()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    parseBinaryMessage(bytes.toByteArray())
                } catch (e: Exception) {
                    Log.w(TAG, "解析二进制消息失败: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "收到文本消息: ${text.take(200)}")
                try {
                    parseResponseJson(JSONObject(text))
                } catch (e: Exception) {
                    Log.w(TAG, "解析文本消息失败: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 关闭中: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 已关闭: $code $reason")
                // 非正常关闭时尝试重连
                if (code != 1000 && _engineStatus.value.state == EngineState.RUNNING) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 失败: ${t.message}, response=${response?.code}")
                if (_engineStatus.value.state == EngineState.RUNNING) {
                    scheduleReconnect()
                } else {
                    audioBuffer.clear()
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "连接失败: ${t.message}")
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "已达最大重连次数 ($MAX_RECONNECT_ATTEMPTS)，放弃重连")
            audioBuffer.clear()
            _engineStatus.value = EngineStatus(EngineState.ERROR, "连接失败，已重试 $reconnectAttempts 次")
            return
        }

        reconnectAttempts++
        val delay = (RECONNECT_BASE_DELAY_MS * reconnectAttempts).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        Log.i(TAG, "将在 ${delay}ms 后尝试第 $reconnectAttempts 次重连...")
        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在重连 ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")

        val config = reconnectConfig ?: return

        reconnectScope?.launch {
            delay(delay)
            openWebSocket(config)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 帧构建 / 压缩
    // ═══════════════════════════════════════════════════════════

    private fun buildFrame(messageType: Byte, flags: Byte, payload: ByteArray): ByteArray {
        val size = payload.size
        val frame = ByteArray(HEADER_SIZE + 4 + size)
        frame[0] = PROTOCOL_VERSION
        frame[1] = HEADER_SIZE.toByte()
        frame[2] = messageType
        frame[3] = flags
        frame[4] = ((size shr 24) and 0xFF).toByte()
        frame[5] = ((size shr 16) and 0xFF).toByte()
        frame[6] = ((size shr 8) and 0xFF).toByte()
        frame[7] = (size and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 8, size)
        return frame
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gzipDecompress(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }

    // ═══════════════════════════════════════════════════════════
    // 客户端请求
    // ═══════════════════════════════════════════════════════════

    private fun sendFullClientRequest(languageCode: String) {
        try {
            val requestJson = JSONObject().apply {
                put("app", JSONObject().apply {
                    put("appid", "")
                    put("cluster", "")
                })
                put("user", JSONObject().apply {
                    put("uid", connectId)
                })
                put("audio", JSONObject().apply {
                    put("format", "pcm")
                    put("rate", 16000)
                    put("bits", 16)
                    put("channel", 1)
                    put("language", languageCode)
                    put("codec", "raw")
                })
                put("request", JSONObject().apply {
                    put("model_name", "bigmodel")
                    put("enable_punctuation", true)
                    put("enable_itn", true)
                    put("show_utterances", true)
                    put("result_type", "single")
                    put("request_id", connectId)
                })
            }

            val jsonBytes = requestJson.toString().toByteArray(Charsets.UTF_8)
            val compressed = gzipCompress(jsonBytes)
            val frame = buildFrame(MSG_FULL_REQUEST, FLAG_JSON_GZIP, compressed)

            webSocket?.send(ByteString.of(*frame))
            Log.i(TAG, "已发送完整客户端请求")
        } catch (e: Exception) {
            Log.e(TAG, "发送初始请求失败: ${e.message}")
            _engineStatus.value = EngineStatus(EngineState.ERROR, "发送请求失败: ${e.message}")
        }
    }

    private fun sendAudioLastFrame() {
        try {
            val frame = buildFrame(MSG_AUDIO_LAST, FLAG_RAW, ByteArray(0))
            webSocket?.send(ByteString.of(*frame))
            Log.i(TAG, "已发送音频结束帧")
        } catch (e: Exception) {
            Log.w(TAG, "发送音频结束帧失败: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 缓冲管理
    // ═══════════════════════════════════════════════════════════

    private fun flushBuffer() {
        var sent = 0
        // 内存缓冲
        while (audioBuffer.isNotEmpty()) {
            val data = audioBuffer.poll() ?: break
            try {
                val frame = buildFrame(MSG_AUDIO_ONLY, FLAG_RAW, data)
                webSocket?.send(ByteString.of(*frame))
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "缓冲帧发送失败: ${e.message}")
                break
            }
        }
        // 溢出文件
        overflowFile?.let { file ->
            try {
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(AUDIO_FRAME_SIZE)
                    while (true) {
                        val read = fis.read(buffer)
                        if (read <= 0) break
                        val chunk = if (read == AUDIO_FRAME_SIZE) buffer.copyOf()
                        else buffer.copyOfRange(0, read)
                        try {
                            val frame = buildFrame(MSG_AUDIO_ONLY, FLAG_RAW, chunk)
                            webSocket?.send(ByteString.of(*frame))
                            sent++
                        } catch (e: Exception) {
                            Log.w(TAG, "溢出帧发送失败: ${e.message}")
                            break
                        }
                    }
                }
                file.delete()
            } catch (e: Exception) {
                Log.w(TAG, "读取溢出文件失败: ${e.message}")
            }
        }
        overflowFile = null
        overflowOutputStream = null
        if (sent > 0) Log.i(TAG, "已重放 $sent 帧缓冲音频")
    }

    private fun writeToOverflowFile(pcmData: ByteArray) {
        try {
            if (overflowFile == null) {
                overflowFile = File(
                    appContext?.cacheDir, "volc_audio_overflow_${System.currentTimeMillis()}.pcm"
                )
                overflowOutputStream = FileOutputStream(overflowFile, true)
            }
            overflowOutputStream?.write(pcmData)
        } catch (e: Exception) {
            Log.w(TAG, "写入溢出文件失败: ${e.message}")
        }
    }

    private fun cleanupOverflowFile() {
        try { overflowOutputStream?.close() } catch (_: Exception) {}
        overflowOutputStream = null
        overflowFile?.delete()
        overflowFile = null
    }

    // ═══════════════════════════════════════════════════════════
    // 响应解析
    // ═══════════════════════════════════════════════════════════

    private fun parseBinaryMessage(data: ByteArray) {
        if (data.size < 8) return

        val msgType = data[2]
        val flags = data[3]
        val payloadSize = ((data[4].toInt() and 0xFF) shl 24) or
                ((data[5].toInt() and 0xFF) shl 16) or
                ((data[6].toInt() and 0xFF) shl 8) or
                (data[7].toInt() and 0xFF)

        if (data.size < 8 + payloadSize) return

        when (msgType) {
            MSG_SERVER_RESPONSE -> {
                val json = extractJsonPayload(data, 8, payloadSize, flags)
                if (json != null) parseResponseJson(json)
            }
            MSG_SERVER_ERROR -> {
                val json = extractJsonPayload(data, 8, payloadSize, flags)
                val errorMsg = json?.optString("message",
                    json?.optString("error", "未知错误") ?: "未知错误")
                Log.e(TAG, "服务端错误帧: $errorMsg")
                _engineStatus.value = EngineStatus(EngineState.ERROR, "服务端错误: $errorMsg")
            }
            else -> {
                // 尝试作为文本解析
                try {
                    val raw = String(data.copyOfRange(8, 8 + payloadSize), Charsets.UTF_8)
                    if (raw.trimStart().startsWith("{")) {
                        parseResponseJson(JSONObject(raw))
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun extractJsonPayload(data: ByteArray, offset: Int, size: Int, flags: Byte): JSONObject? {
        return try {
            val raw = data.copyOfRange(offset, offset + size)
            val isGzip = (flags.toInt() and FLAG_GZIP_MASK.toInt()) != 0
            val jsonBytes = if (isGzip) gzipDecompress(raw) else raw
            JSONObject(String(jsonBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseResponseJson(json: JSONObject) {
        Log.d(TAG, "响应 JSON: ${json.toString().take(400)}")

        val payload = json.optJSONObject("payload_msg")
            ?: json.optJSONObject("payload")

        if (payload != null) {
            val code = payload.optInt("code", -1)
            if (code != 0 && code != -1) {
                _engineStatus.value = EngineStatus(
                    EngineState.ERROR,
                    "code=$code: ${payload.optString("message", "未知错误")}"
                )
                return
            }

            val results = payload.optJSONArray("result")
            if (results != null && results.length() > 0) {
                processResults(results)
                return
            }
        }

        val topResults = json.optJSONArray("result")
        if (topResults != null && topResults.length() > 0) {
            processResults(topResults)
            return
        }

        val topText = json.optString("text", "")
        if (topText.isNotBlank()) {
            _interimText.value = topText.trim()
        }
    }

    private fun processResults(results: JSONArray) {
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            val text = result.optString("text", "")
            val isFinal = result.optBoolean("is_final", false)
            val utterances = result.optJSONArray("utterances")

            if (isFinal && text.isNotBlank()) {
                sentenceCounter++
                val (speakerId, startMs, endMs) = extractUtteranceMeta(utterances)
                _sentenceResults.tryEmit(
                    AsrSentence(
                        text = text.trim(),
                        sentenceId = sentenceCounter,
                        speakerId = speakerId,
                        startTimeMs = startMs,
                        endTimeMs = endMs,
                        isFinal = true
                    )
                )
            } else if (text.isNotBlank()) {
                _interimText.value = text.trim()
            } else if (utterances != null && utterances.length() > 0) {
                val first = utterances.optJSONObject(0)
                val uttText = first?.optString("text", "") ?: ""
                if (uttText.isNotBlank()) {
                    if (isFinal) {
                        sentenceCounter++
                        val (sid, sMs, eMs) = extractUtteranceMeta(utterances)
                        _sentenceResults.tryEmit(
                            AsrSentence(
                                text = uttText.trim(),
                                sentenceId = sentenceCounter,
                                speakerId = sid,
                                startTimeMs = sMs,
                                endTimeMs = eMs,
                                isFinal = true
                            )
                        )
                    } else {
                        _interimText.value = uttText.trim()
                    }
                }
            }
        }
    }

    private fun extractUtteranceMeta(utterances: JSONArray?): Triple<String, Long, Long> {
        if (utterances == null || utterances.length() == 0) return Triple("0", 0L, 0L)
        val first = utterances.optJSONObject(0) ?: return Triple("0", 0L, 0L)
        return Triple(
            first.optString("speaker", "0"),
            first.optLong("start_time", 0L),
            first.optLong("end_time", 0L)
        )
    }

    companion object {
        private const val TAG = "VolcengineEngine"

        // WebSocket
        private const val WS_URL = "wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async"
        private const val RESOURCE_ID = "volc.seedasr.sauc.duration"

        // 缓冲
        private const val MAX_BUFFER_SIZE = 1200
        private const val AUDIO_FRAME_SIZE = 3200

        // 二进制帧
        private const val MSG_FULL_REQUEST: Byte = 0x11.toByte()
        private const val MSG_AUDIO_ONLY: Byte = 0x12.toByte()
        private const val MSG_AUDIO_LAST: Byte = 0x13.toByte()
        private const val MSG_SERVER_RESPONSE: Byte = 0x91.toByte()
        private const val MSG_SERVER_ERROR: Byte = 0x92.toByte()

        private const val FLAG_JSON_GZIP: Byte = 0x30.toByte()
        private const val FLAG_RAW: Byte = 0x00
        private const val FLAG_GZIP_MASK: Byte = 0x20.toByte()

        private const val HEADER_SIZE = 4
        private const val PROTOCOL_VERSION: Byte = 0x01

        /** WebSocket 重连配置 */
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 1000L
        private const val RECONNECT_MAX_DELAY_MS = 15000L
    }
}
