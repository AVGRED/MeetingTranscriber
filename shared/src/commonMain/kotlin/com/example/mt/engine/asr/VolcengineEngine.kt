package com.example.mt.engine.asr

import com.example.mt.config.EngineKeys
import com.example.mt.engine.*
import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 豆包/火山引擎云端 ASR 引擎（跨平台，无 Android 依赖）。
 *
 * 基于官方 WebSocket 二进制协议（docs/6561/1354869）:
 * - URL: bigmodel_async（双向流式优化版）
 * - 新控制台鉴权: X-Api-Key
 * - 帧头: 4 字节 nibble 位域 + 4 字节 payload size
 */
class VolcengineEngine(
    private val keys: EngineKeys,
) : AsrEngine {

    override val type: AsrEngineType = AsrEngineType.VOLCENGINE_CLOUD

    // ── 内部状态 ──
    @Volatile private var webSocket: WebSocket? = null
    private var connectId: String = ""
    private var sentenceCounter: Long = 0
    private var audioSeq: Int = 0
    private val webSocketReady = AtomicBoolean(false)

    private val audioBuffer = Channel<ByteArray>(EngineConstants.MAX_BUFFER_FRAMES)

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

    override suspend fun initialize(): Result<Unit> {
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return Result.success(Unit)
        }

        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在验证豆包 ASR 密钥...")

        if (!keys.hasVolcengineKeys()) {
            val msg = "豆包 ASR 密钥未配置 (需 API Key 或 Access Token)"
            Napier.w("$TAG: $msg")
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            return Result.failure(IllegalStateException(msg))
        }

        _engineStatus.value = EngineStatus(EngineState.READY, "豆包 ASR 已就绪")
        Napier.i("$TAG: 豆包 ASR 引擎初始化成功")
        Result.success(Unit)
    }

    override suspend fun start(config: AsrConfig): Result<Unit> {
        return try {
            connectId = UUID.randomUUID().toString()
            sentenceCounter = 0
            audioSeq = 0
            _interimText.value = ""
            webSocketReady.set(false)
            reconnectHandler.reset()
            reconnectConfig = config

            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在连接豆包 ASR...")
            openWebSocket(config)

            _engineStatus.value = EngineStatus(EngineState.RUNNING)
            Result.success(Unit)
        } catch (e: Exception) {
            Napier.e("$TAG: 启动豆包 ASR 失败", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "启动失败: ${e.message}")
            Result.failure(e)
        }
    }

    override fun processAudio(pcmData: ByteArray) {
        if (_engineStatus.value.state != EngineState.RUNNING) return

        if (webSocket != null && webSocketReady.get()) {
            try {
                val compressed = gzipCompress(pcmData)
                audioSeq++
                val frame = buildAudioFrame(compressed, audioSeq, last = false)
                webSocket?.send(ByteString.of(*frame))
            } catch (e: Exception) {
                Napier.w("$TAG: 发送音频帧失败: ${e.message}")
            }
        } else {
            // 未就绪 → 缓冲
            audioBuffer.trySend(pcmData.copyOf())
        }
    }

    override suspend fun finalize() {
        webSocketReady.set(false)
        try {
            val lastFrame = buildClientFrame(MSG_AUDIO_ONLY, FLAG_LAST, SER_NONE, COMP_NONE, ByteArray(0))
            webSocket?.send(ByteString.of(*lastFrame))
            Napier.i("$TAG: 已发送音频结束帧")
            delay(1500)
            webSocket?.close(1000, "用户结束会议")
        } catch (_: Exception) {}
        webSocket = null
        // 清空缓冲
        while (audioBuffer.tryReceive().isSuccess) { /* drain */ }
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.READY, "转写已结束")
        Napier.i("$TAG: 豆包 ASR finalize 完成")
    }

    override suspend fun dispose() {
        webSocketReady.set(false)
        reconnectHandler.cancel()
        reconnectConfig = null
        try { webSocket?.close(1000, "引擎释放") } catch (_: Exception) {}
        webSocket = null
        while (audioBuffer.tryReceive().isSuccess) { /* drain */ }
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        Napier.i("$TAG: 豆包 ASR 引擎已释放")
    }

    // ═══════════════════════════════════════════════════════════
    // WebSocket 管理 + 重连
    // ═══════════════════════════════════════════════════════════

    private val reconnectHandler = ReconnectHandler(TAG)
    @Volatile private var reconnectConfig: AsrConfig? = null

    private fun openWebSocket(config: AsrConfig) {
        val languageCode = if (config.language == "cn") "zh-CN" else config.language

        val request = Request.Builder()
            .url(WS_URL)
            .apply {
                val apiKey = keys.volcengineAsrApiKey
                header("X-Api-Key", apiKey)
                header("X-Api-App-Key", apiKey)
            }
            .header("X-Api-Resource-Id", RESOURCE_ID)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Connect-Id", connectId)
            .build()

        Napier.i("$TAG: 打开 WebSocket: resource=$RESOURCE_ID")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Napier.i("$TAG: WebSocket 已连接, status=${response.code}")
                val logId = response.header("X-Tt-Logid") ?: "N/A"
                Napier.i("$TAG: X-Tt-Logid=$logId")
                reconnectHandler.reset()
                webSocketReady.set(true)
                sendFullClientRequest(languageCode)
                flushBuffer()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    parseBinaryMessage(bytes.toByteArray())
                } catch (e: Exception) {
                    Napier.w("$TAG: 解析二进制消息失败: ${e.message}", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Napier.d("$TAG: 收到文本消息: ${text.take(200)}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Napier.i("$TAG: WebSocket 关闭中: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Napier.i("$TAG: WebSocket 已关闭: $code $reason")
                webSocketReady.set(false)
                if (code != 1000 && _engineStatus.value.state == EngineState.RUNNING) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Napier.e("$TAG: WebSocket 失败: ${t.message}, response=${response?.code}")
                webSocketReady.set(false)
                if (_engineStatus.value.state == EngineState.RUNNING) {
                    scheduleReconnect()
                } else {
                    while (audioBuffer.tryReceive().isSuccess) { /* drain */ }
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "连接失败: ${t.message}")
                }
            }
        })
    }

    private fun scheduleReconnect() {
        val config = reconnectConfig ?: return

        if (reconnectHandler.isExhausted) {
            Napier.e("$TAG: 已达最大重连次数，放弃重连")
            while (audioBuffer.tryReceive().isSuccess) { /* drain */ }
            _engineStatus.value = EngineStatus(EngineState.ERROR,
                "连接失败，已重试 ${EngineConstants.MAX_RECONNECT_ATTEMPTS} 次")
            return
        }

        webSocketReady.set(false)
        reconnectHandler.start { attempt ->
            audioSeq = 0
            _engineStatus.value = EngineStatus(EngineState.LOADING,
                "正在重连 ($attempt/${EngineConstants.MAX_RECONNECT_ATTEMPTS})...")
            openWebSocket(config)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 二进制帧构建 — 官方 header 格式（4 字节 nibble 位域）
    // ═══════════════════════════════════════════════════════════

    private fun buildHeader(msgType: Int, flags: Int, serialization: Int, compression: Int): ByteArray {
        return byteArrayOf(
            ((PROTOCOL_VERSION shl 4) or HEADER_SIZE).toByte(),
            ((msgType shl 4) or (flags and 0x0F)).toByte(),
            ((serialization shl 4) or (compression and 0x0F)).toByte(),
            0x00.toByte(),
        )
    }

    private fun int32BE(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    private fun readInt32BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun buildClientFrame(
        msgType: Int, flags: Int, serialization: Int, compression: Int, payload: ByteArray,
    ): ByteArray {
        val header = buildHeader(msgType, flags, serialization, compression)
        val sizeBytes = int32BE(payload.size)
        return header + sizeBytes + payload
    }

    private fun buildAudioFrame(compressedAudio: ByteArray, seq: Int, last: Boolean): ByteArray {
        val flags = if (last) FLAG_LAST else FLAG_NONE
        return buildClientFrame(MSG_AUDIO_ONLY, flags, SER_NONE, COMP_GZIP, compressedAudio)
    }

    // ═══════════════════════════════════════════════════════════
    // 压缩工具
    // ═══════════════════════════════════════════════════════════

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
                put("user", JSONObject().apply { put("uid", connectId) })
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
                    put("enable_punc", true)
                    put("enable_itn", true)
                    put("enable_ddc", true)
                    put("show_utterances", true)
                    put("result_type", "single")
                    put("enable_speaker_info", true)
                    put("enable_nonstream", true)
                    put("end_window_size", 800)
                })
            }

            val jsonBytes = requestJson.toString().toByteArray(Charsets.UTF_8)
            val compressed = gzipCompress(jsonBytes)
            val frame = buildClientFrame(MSG_FULL_REQUEST, FLAG_NONE, SER_JSON, COMP_GZIP, compressed)

            webSocket?.send(ByteString.of(*frame))
            Napier.i("$TAG: 已发送完整客户端请求 (${jsonBytes.size}B → ${compressed.size}B Gzip)")
        } catch (e: Exception) {
            Napier.e("$TAG: 发送初始请求失败: ${e.message}", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "发送请求失败: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 缓冲管理（Channel 替代 ConcurrentLinkedQueue + 溢出文件）
    // ═══════════════════════════════════════════════════════════

    private fun flushBuffer() {
        var sent = 0
        while (true) {
            val data = audioBuffer.tryReceive().getOrNull() ?: break
            try {
                val compressed = gzipCompress(data)
                audioSeq++
                val frame = buildAudioFrame(compressed, audioSeq, last = false)
                webSocket?.send(ByteString.of(*frame))
                sent++
            } catch (e: Exception) {
                Napier.w("$TAG: 缓冲帧发送失败: ${e.message}")
                break
            }
        }
        if (sent > 0) Napier.i("$TAG: 已重放 $sent 帧缓冲音频")
    }

    // ═══════════════════════════════════════════════════════════
    // 响应解析
    // ═══════════════════════════════════════════════════════════

    private fun parseBinaryMessage(data: ByteArray) {
        if (data.size < 8) return

        val msgType = (data[1].toInt() shr 4) and 0x0F
        val flags = data[1].toInt() and 0x0F
        val serialization = (data[2].toInt() shr 4) and 0x0F
        val compression = data[2].toInt() and 0x0F

        when (msgType) {
            MSG_SERVER_RESPONSE -> {
                if (data.size < 12) return
                val sequence = readInt32BE(data, 4)
                val payloadSize = readInt32BE(data, 8)
                if (data.size < 12 + payloadSize) return
                val payload = data.copyOfRange(12, 12 + payloadSize)
                val decompressed = if (compression == COMP_GZIP) gzipDecompress(payload) else payload
                val jsonString = String(decompressed, Charsets.UTF_8)
                Napier.d("$TAG: 响应 [seq=$sequence, flags=$flags]: ${jsonString.take(300)}")
                try {
                    parseResponseJson(JSONObject(jsonString), flags)
                } catch (e: Exception) {
                    Napier.w("$TAG: 解析响应 JSON 失败: ${e.message}")
                }
            }
            MSG_SERVER_ERROR -> {
                if (data.size < 12) return
                val errorCode = readInt32BE(data, 4)
                val errorSize = readInt32BE(data, 8)
                if (data.size < 12 + errorSize) return
                val errorMsg = String(data.copyOfRange(12, 12 + errorSize), Charsets.UTF_8)
                Napier.e("$TAG: 服务端错误 [$errorCode]: $errorMsg")
                _engineStatus.value = EngineStatus(EngineState.ERROR, "服务端错误: $errorMsg")
            }
            else -> {
                Napier.w("$TAG: 未知消息类型: $msgType")
            }
        }
    }

    private fun parseResponseJson(json: JSONObject, flags: Int) {
        val audioInfo = json.optJSONObject("audio_info")
        val result = json.optJSONObject("result") ?: run {
            if (audioInfo != null) {
                Napier.d("$TAG: 仅含 audio_info: duration=${audioInfo.optInt("duration")}")
            }
            return
        }

        val utterances = result.optJSONArray("utterances")
        val fullText = result.optString("text", "")

        if (utterances != null && utterances.length() > 0) {
            for (i in 0 until utterances.length()) {
                val utt = utterances.optJSONObject(i) ?: continue
                val uttText = utt.optString("text", "")
                val definite = utt.optBoolean("definite", false)
                val startMs = utt.optLong("start_time", 0L)
                val endMs = utt.optLong("end_time", 0L)

                if (uttText.isBlank()) continue

                if (definite) {
                    sentenceCounter++
                    _sentenceResults.tryEmit(
                        AsrSentence(
                            text = uttText.trim(),
                            sentenceId = sentenceCounter,
                            speakerId = extractSpeakerId(utt),
                            startTimeMs = startMs,
                            endTimeMs = endMs,
                            isFinal = true,
                        )
                    )
                    Napier.d("$TAG: 分句: \"${uttText.trim().take(30)}...\"")
                } else {
                    _interimText.value = uttText.trim()
                }
            }
        }

        if (fullText.isNotBlank() && (utterances == null || utterances.length() == 0)) {
            val isServerFinal = (flags and FLAG_SERVER_FINAL) != 0
            if (isServerFinal) {
                sentenceCounter++
                _sentenceResults.tryEmit(
                    AsrSentence(
                        text = fullText.trim(),
                        sentenceId = sentenceCounter,
                        speakerId = "0",
                        startTimeMs = 0L,
                        endTimeMs = audioInfo?.optLong("duration", 0L) ?: 0L,
                        isFinal = true,
                    )
                )
            } else {
                _interimText.value = fullText.trim()
            }
        }
    }

    private fun extractSpeakerId(utt: JSONObject): String {
        val words = utt.optJSONArray("words") ?: return "0"
        for (i in 0 until words.length()) {
            val w = words.optJSONObject(i) ?: continue
            val sid = w.optString("speaker", "")
            if (sid.isNotBlank()) return sid
        }
        return "0"
    }

    // ═══════════════════════════════════════════════════════════
    // 常量
    // ═══════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "VolcengineEngine"
        private const val WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        private const val RESOURCE_ID = "volc.seedasr.sauc.duration"

        // Header 位域
        private const val PROTOCOL_VERSION = 1
        private const val HEADER_SIZE = 1

        // 消息类型
        private const val MSG_FULL_REQUEST = 0x1
        private const val MSG_AUDIO_ONLY = 0x2
        private const val MSG_SERVER_RESPONSE = 0x9
        private const val MSG_SERVER_ERROR = 0xF

        // 标志位
        private const val FLAG_NONE = 0x0
        private const val FLAG_POS_SEQ = 0x1
        private const val FLAG_LAST = 0x2
        private const val FLAG_SERVER_FINAL = 0x3

        // 序列化/压缩
        private const val SER_NONE = 0x0
        private const val SER_JSON = 0x1
        private const val COMP_NONE = 0x0
        private const val COMP_GZIP = 0x1
    }
}
