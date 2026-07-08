package com.example.meetingtranscriber.network

import android.util.Log
import com.example.meetingtranscriber.BuildConfig
import com.example.meetingtranscriber.MeetingApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
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
 * 火山引擎 / 豆包（Volcengine/Doubao）ASR Provider。
 *
 * 使用大模型流式 ASR（bigmodel）协议，通过二进制 WebSocket 帧与
 * wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async 通信。
 *
 * 认证：
 * - 优先使用 X-Api-Key 头（VOLCENGINE_ASR_API_KEY）
 * - 兼容 X-Api-Access-Token（VOLCENGINE_ASR_ACCESS_TOKEN）
 *
 * 协议帧格式（大端）：
 *   [version:1][header_size:1][msg_type:1][flags:1][payload_len:4][payload:N]
 *
 * 当前不会作为默认 Provider；上层需显式选择。
 */
class VolcengineAsrProvider : AsrProvider {

    companion object {
        private const val TAG = "VolcengineAsrProvider"

        // ── 默认值 ──
        private const val DEFAULT_SEED_RESOURCE_ID = "volc.seedasr.sauc.duration"
        private const val DEFAULT_LEGACY_RESOURCE_ID = "volc.bigasr.sauc.duration"
        private const val DEFAULT_WS_URL = "wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async"

        // ── 缓冲 ──
        private const val MAX_BUFFER_SIZE = 1200  // 约 120 秒 @ 100ms/帧
        private const val AUDIO_FRAME_SIZE = 3200  // 100ms @ 16kHz/16bit/mono

        // ── 二进制帧消息类型 ──
        // TODO: client→server 0x11/0x12/0x13 verified against docs;
        //       server→client 0x91/0x92 are best-guess based on common patterns
        private const val MSG_FULL_REQUEST: Byte = 0x11.toByte()
        private const val MSG_AUDIO_ONLY: Byte = 0x12.toByte()
        private const val MSG_AUDIO_LAST: Byte = 0x13.toByte()
        private const val MSG_SERVER_RESPONSE: Byte = 0x91.toByte()
        private const val MSG_SERVER_ERROR: Byte = 0x92.toByte()

        // ── 标志位 ──
        private const val FLAG_JSON_GZIP: Byte = 0x30.toByte()   // 0x10 JSON | 0x20 gzip
        private const val FLAG_RAW: Byte = 0x00                  // 无序列化、无压缩（原始音频）
        private const val FLAG_GZIP_MASK: Byte = 0x20.toByte()

        // ── 协议常量 ──
        private const val HEADER_SIZE = 4
        private const val PROTOCOL_VERSION: Byte = 0x01

        private const val HEADER_STATUS_CODE = "X-Api-Status-Code"
        private const val HEADER_MESSAGE = "X-Api-Message"
        private const val HEADER_LOG_ID = "X-Tt-Logid"
    }

    private enum class AuthMode {
        API_KEY,
        LEGACY
    }

    // ═══════════════════════════════════════════════════════════
    // 配置
    // ═══════════════════════════════════════════════════════════

    /** API Key（新版单 Key 鉴权），不做旧版 Access Token 回退 */
    private val apiKey: String
        get() = BuildConfig.VOLCENGINE_ASR_API_KEY

    /** 旧版鉴权是否可用 */
    private val hasLegacyAuth: Boolean
        get() = BuildConfig.VOLCENGINE_ASR_APP_ID.isNotBlank() &&
                BuildConfig.VOLCENGINE_ASR_ACCESS_TOKEN.isNotBlank()

    private val wsUrl: String
        get() = BuildConfig.VOLCENGINE_ASR_WS_URL.ifBlank { DEFAULT_WS_URL }

    private val preferredAuthMode: AuthMode
        get() = if (apiKey.isNotBlank()) AuthMode.API_KEY else AuthMode.LEGACY

    // ═══════════════════════════════════════════════════════════
    // 状态
    // ═══════════════════════════════════════════════════════════

    private var webSocket: WebSocket? = null
    @Volatile private var connectionState = ConnectionState.DISCONNECTED
    private var connectId: String = ""
    @Volatile private var sentenceCounter: Long = 0

    // 缓冲（复用 Tingwu 客户端模式：内存队列 + 溢出文件）
    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()
    private var overflowFile: File? = null
    private var overflowOutputStream: FileOutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)      // 流式，无读取超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)            // 二进制 WS 不自动重试
        .build()

    // ═══════════════════════════════════════════════════════════
    // AsrProvider 回调
    // ═══════════════════════════════════════════════════════════

    override var onInterimResult: ((String) -> Unit)? = null
    override var onSentenceResult: ((AsrSentenceResult) -> Unit)? = null
    override var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    // ═══════════════════════════════════════════════════════════
    // 配置校验
    // ═══════════════════════════════════════════════════════════

    private fun validateConfig(): String? {
        // 新版 API Key 或旧版 App ID + Access Token，二者满足其一即可
        if (apiKey.isNotBlank()) return null
        if (hasLegacyAuth) return null
        return "Volcengine 鉴权未配置" +
                "（需设置 VOLCENGINE_ASR_API_KEY 或 VOLCENGINE_ASR_APP_ID + VOLCENGINE_ASR_ACCESS_TOKEN）"
    }

    private fun resourceIdFor(mode: AuthMode): String {
        val configured = BuildConfig.VOLCENGINE_ASR_RESOURCE_ID
        if (configured.isBlank()) {
            return when (mode) {
                AuthMode.API_KEY -> DEFAULT_SEED_RESOURCE_ID
                AuthMode.LEGACY -> DEFAULT_LEGACY_RESOURCE_ID
            }
        }

        return if (mode == AuthMode.LEGACY && configured == DEFAULT_SEED_RESOURCE_ID) {
            Log.w(TAG, "旧版鉴权不使用 $configured，已切换为 $DEFAULT_LEGACY_RESOURCE_ID")
            DEFAULT_LEGACY_RESOURCE_ID
        } else {
            configured
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 帧构建/压缩工具
    // ═══════════════════════════════════════════════════════════

    /**
     * 构建二进制协议帧: 4 字节头 + 4 字节 payload 长度（大端） + payload。
     */
    private fun buildFrame(messageType: Byte, flags: Byte, payload: ByteArray): ByteArray {
        val size = payload.size
        val frame = ByteArray(HEADER_SIZE + 4 + size)
        frame[0] = PROTOCOL_VERSION
        frame[1] = HEADER_SIZE.toByte()
        frame[2] = messageType
        frame[3] = flags
        // Payload 长度，大端 uint32
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
    // AsrProvider: start
    // ═══════════════════════════════════════════════════════════

    override suspend fun start(config: AsrConfig): Boolean {
        validateConfig()?.let { msg ->
            Log.e(TAG, msg)
            onError?.invoke(msg)
            return false
        }

        connectId = UUID.randomUUID().toString()
        sentenceCounter = 0

        return try {
            openWebSocket(config, preferredAuthMode)
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动 Volcengine ASR 失败: ${e.message}")
            onError?.invoke("启动失败: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════
    // WebSocket 管理
    // ═══════════════════════════════════════════════════════════

    private fun openWebSocket(config: AsrConfig, authMode: AuthMode) {
        updateState(ConnectionState.CONNECTING)

        // 语言代码映射: "cn" → "zh-CN"（TODO: 验证 Volcengine 标准语言代码列表）
        val languageCode = if (config.language == "cn") "zh-CN" else config.language
        val requestId = UUID.randomUUID().toString()
        val selectedResourceId = resourceIdFor(authMode)

        val request = Request.Builder()
            .url(wsUrl)
            .apply {
                when (authMode) {
                    AuthMode.API_KEY -> {
                        header("X-Api-Key", apiKey)
                    }
                    AuthMode.LEGACY -> {
                        val appId = BuildConfig.VOLCENGINE_ASR_APP_ID
                        val accessToken = BuildConfig.VOLCENGINE_ASR_ACCESS_TOKEN
                        if (appId.isNotBlank()) header("X-Api-App-Key", appId)
                        if (accessToken.isNotBlank()) header("X-Api-Access-Key", accessToken)
                    }
                }
            }
            .header("X-Api-Resource-Id", selectedResourceId)
            .header("X-Api-Request-Id", requestId)
            .header("X-Api-Connect-Id", connectId)
            .header("X-Api-Sequence", "-1")
            .build()

        Log.i(
            TAG,
            "打开 WebSocket: auth=$authMode, resource=$selectedResourceId, url=$wsUrl, requestId=$requestId"
        )

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接, status=${response.code}, auth=$authMode")
                updateState(ConnectionState.CONNECTED)
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
                // 某些场景下服务端可能返回文本 JSON
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
                updateState(ConnectionState.DISCONNECTED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val serverMessage = formatHandshakeFailure(response)
                Log.e(TAG, "WebSocket 失败: ${t.message}, auth=$authMode, $serverMessage")

                if (authMode == AuthMode.API_KEY && response?.code == 403 && hasLegacyAuth) {
                    Log.w(TAG, "新版 API Key 被拒绝，尝试切换到旧版鉴权")
                    openWebSocket(config, AuthMode.LEGACY)
                    return
                }

                audioBuffer.clear()
                updateState(ConnectionState.FAILED)
                onError?.invoke("连接失败: ${t.message}；$serverMessage")
            }
        })
    }

    private fun formatHandshakeFailure(response: Response?): String {
        if (response == null) return "response=null"

        val statusCode = response.header(HEADER_STATUS_CODE)
        val message = response.header(HEADER_MESSAGE)
        val logId = response.header(HEADER_LOG_ID)

        return buildString {
            append("response=${response.code}")
            if (!statusCode.isNullOrBlank()) append(", $HEADER_STATUS_CODE=$statusCode")
            if (!message.isNullOrBlank()) append(", $HEADER_MESSAGE=$message")
            if (!logId.isNullOrBlank()) append(", $HEADER_LOG_ID=$logId")
        }
    }

    /**
     * 发送完整客户端请求帧（0x11，gzip 压缩 JSON）。
     * 包含 app / user / audio / request 四个部分。
     */
    private fun sendFullClientRequest(languageCode: String) {
        try {
            // TODO: 以下字段名基于公开文档；若实际服务端行为不同需调整
            val requestJson = JSONObject().apply {
                put("app", JSONObject().apply {
                    // X-Api-Key 头已承载认证，appid/cluster 可能冗余但保留兼容
                    put("appid", BuildConfig.VOLCENGINE_ASR_APP_ID.ifBlank { "" })
                    put("cluster", BuildConfig.VOLCENGINE_ASR_CLUSTER.ifBlank { "" })
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
                    put("model_name", "bigmodel")            // TODO: 确认模型名是否固定为 "bigmodel"
                    put("enable_punctuation", true)
                    put("enable_itn", true)                // 逆文本正则化
                    // TODO: show_utterances / result_type 字段名待服务端确认
                    put("show_utterances", true)
                    put("result_type", "single")
                    put("request_id", connectId)            // 用于链路追踪
                })
            }

            val jsonBytes = requestJson.toString().toByteArray(Charsets.UTF_8)
            val compressed = gzipCompress(jsonBytes)
            val frame = buildFrame(MSG_FULL_REQUEST, FLAG_JSON_GZIP, compressed)

            webSocket?.send(ByteString.of(*frame))
            Log.i(TAG, "已发送完整客户端请求")
        } catch (e: Exception) {
            Log.e(TAG, "发送初始请求失败: ${e.message}")
            onError?.invoke("发送初始请求失败: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // AsrProvider: sendAudio
    // ═══════════════════════════════════════════════════════════

    override fun sendAudio(pcmData: ByteArray) {
        if (connectionState == ConnectionState.CONNECTED) {
            try {
                val frame = buildFrame(MSG_AUDIO_ONLY, FLAG_RAW, pcmData)
                webSocket?.send(ByteString.of(*frame))
            } catch (e: Exception) {
                Log.w(TAG, "发送音频帧失败: ${e.message}")
            }
        } else {
            // 未连接时缓冲：先填内存队列，满了落盘
            if (audioBuffer.size < MAX_BUFFER_SIZE) {
                audioBuffer.offer(pcmData.copyOf())
            } else {
                writeToOverflowFile(pcmData)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 缓冲溢出
    // ═══════════════════════════════════════════════════════════

    private fun writeToOverflowFile(pcmData: ByteArray) {
        try {
            if (overflowFile == null) {
                val dir = MeetingApplication.instance.cacheDir
                overflowFile = File(dir, "volc_audio_overflow_${System.currentTimeMillis()}.pcm")
                overflowOutputStream = FileOutputStream(overflowFile, true)
            }
            overflowOutputStream?.write(pcmData)
        } catch (e: Exception) {
            Log.w(TAG, "写入溢出文件失败: ${e.message}")
        }
    }

    /** WebSocket 就绪后重放缓冲帧。 */
    private fun flushBuffer() {
        val sentMemory = flushMemoryBuffer()
        val sentDisk = flushOverflowFile()
        val total = sentMemory + sentDisk
        if (total > 0) Log.i(TAG, "已重放 $total 帧缓冲音频（内存:$sentMemory 磁盘:$sentDisk）")
    }

    private fun flushMemoryBuffer(): Int {
        var sent = 0
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
        return sent
    }

    private fun flushOverflowFile(): Int {
        var sent = 0
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
        return sent
    }

    // ═══════════════════════════════════════════════════════════
    // AsrProvider: disconnect / stopTask
    // ═══════════════════════════════════════════════════════════

    override fun disconnect() {
        scope.coroutineContext.cancelChildren()
        sendAudioLastFrame()
        try {
            webSocket?.close(1000, "用户结束会议")
        } catch (_: Exception) {}
        webSocket = null
        audioBuffer.clear()
        cleanupOverflowFile()
        updateState(ConnectionState.DISCONNECTED)
    }

    /**
     * Volcengine bigmodel 没有独立的 REST stop-task 端点；
     * 关闭 WebSocket 即结束本次会话。
     */
    override suspend fun stopTask() {
        disconnect()
    }

    /**
     * 发送音频结束帧（0x13），告知服务端音频流已结束。
     * TODO: 确认 audio-last 帧是否需要携带 JSON payload（如音频格式摘要）。
     *       当前发送空 payload；若服务端要求特定 payload 则需调整。
     */
    private fun sendAudioLastFrame() {
        if (connectionState != ConnectionState.CONNECTED) return
        try {
            val frame = buildFrame(MSG_AUDIO_LAST, FLAG_RAW, ByteArray(0))
            webSocket?.send(ByteString.of(*frame))
            Log.i(TAG, "已发送音频结束帧")
        } catch (e: Exception) {
            Log.w(TAG, "发送音频结束帧失败: ${e.message}")
        }
    }

    private fun cleanupOverflowFile() {
        try { overflowOutputStream?.close() } catch (_: Exception) {}
        overflowOutputStream = null
        overflowFile?.delete()
        overflowFile = null
    }

    // ═══════════════════════════════════════════════════════════
    // AsrProvider: 状态查询
    // ═══════════════════════════════════════════════════════════

    override fun getCurrentTaskId(): String = connectId

    override fun getOverflowFilePath(): String? = overflowFile?.absolutePath

    override fun getBufferSize(): Int = audioBuffer.size

    // ═══════════════════════════════════════════════════════════
    // 二进制响应解析
    // ═══════════════════════════════════════════════════════════

    private fun parseBinaryMessage(data: ByteArray) {
        if (data.size < 8) {
            Log.w(TAG, "响应帧过短: ${data.size} bytes")
            return
        }

        val msgType = data[2]
        val flags = data[3]

        // Payload 长度（大端 uint32）
        val payloadSize = ((data[4].toInt() and 0xFF) shl 24) or
                ((data[5].toInt() and 0xFF) shl 16) or
                ((data[6].toInt() and 0xFF) shl 8) or
                (data[7].toInt() and 0xFF)

        if (data.size < 8 + payloadSize) {
            Log.w(TAG, "响应帧不完整: expected=${8 + payloadSize}, got=${data.size}")
            return
        }

        when (msgType) {
            MSG_SERVER_RESPONSE -> {
                val jsonObj = extractJsonPayload(data, 8, payloadSize, flags)
                if (jsonObj != null) parseResponseJson(jsonObj)
            }
            MSG_SERVER_ERROR -> {
                val jsonObj = extractJsonPayload(data, 8, payloadSize, flags)
                val errorStr = jsonObj?.toString() ?: "(无法解析)"
                Log.e(TAG, "服务端错误帧: $errorStr")
                val message = jsonObj?.optString("message",
                    jsonObj?.optString("error", errorStr) ?: errorStr) ?: errorStr
                onError?.invoke("服务端错误: $message")
            }
            else -> {
                Log.w(TAG, "未知消息类型: 0x${(msgType.toInt() and 0xFF).toString(16)}")
                // 尝试作为未压缩文本解析
                try {
                    val raw = String(data.copyOfRange(8, 8 + payloadSize), Charsets.UTF_8)
                    if (raw.trimStart().startsWith("{")) {
                        parseResponseJson(JSONObject(raw))
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 从二进制帧中提取 JSON payload，必要时解压。
     * @return JSONObject 或 null（解析失败时）
     */
    private fun extractJsonPayload(
        data: ByteArray,
        offset: Int,
        size: Int,
        flags: Byte
    ): JSONObject? {
        return try {
            val raw = data.copyOfRange(offset, offset + size)
            val isGzip = (flags.toInt() and FLAG_GZIP_MASK.toInt()) != 0
            val jsonBytes = if (isGzip) gzipDecompress(raw) else raw
            val jsonStr = String(jsonBytes, Charsets.UTF_8)
            JSONObject(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "提取 JSON payload 失败: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════
    // JSON 响应语义解析
    // ═══════════════════════════════════════════════════════════

    /**
     * 解析服务端 JSON 响应，分发到 interim / final 回调。
     *
     * 尝试多种可能的 JSON 路径（TODO: 所有路径待与真实服务端确认）：
     *   1. payload_msg.result[]        — 预期的主路径
     *   2. payload.result[]            — 备选路径
     *   3. 顶层 result[] / text        — 兜底路径
     */
    private fun parseResponseJson(json: JSONObject) {
        Log.d(TAG, "响应 JSON: ${json.toString().take(400)}")

        // 路径 1 & 2: payload_msg 或 payload 包裹
        val payload = json.optJSONObject("payload_msg")
            ?: json.optJSONObject("payload")

        if (payload != null) {
            // 检查错误码
            val code = payload.optInt("code", -1)
            if (code != 0 && code != -1) {
                val msg = payload.optString("message", "未知错误")
                onError?.invoke("服务端返回错误 (code=$code): $msg")
                return
            }

            val results = payload.optJSONArray("result")
            if (results != null && results.length() > 0) {
                processResults(results)
                return
            }
        }

        // 路径 3: 顶层 result 数组
        val topResults = json.optJSONArray("result")
        if (topResults != null && topResults.length() > 0) {
            processResults(topResults)
            return
        }

        // 路径 4: 顶层 text（简单 interim）
        val topText = json.optString("text", "")
        if (topText.isNotBlank()) {
            onInterimResult?.invoke(topText.trim())
            return
        }

        // 无识别结果 — 可能是连接确认/心跳等
        Log.d(TAG, "非转写响应（可能为状态/心跳）")
    }

    /**
     * 处理 result[] 数组中的每条结果，区分 interim 与 final。
     *
     * Interim 结果 → onInterimResult
     * Final 结果   → onSentenceResult (使用单调递增 sentenceId; 时间戳暂为 0)
     */
    private fun processResults(results: JSONArray) {
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            val text = result.optString("text", "")
            val isFinal = result.optBoolean("is_final", false)
            val utterances = result.optJSONArray("utterances")

            if (isFinal && text.isNotBlank()) {
                sentenceCounter++
                val (speakerId, startMs, endMs) = extractUtteranceMeta(utterances)
                onSentenceResult?.invoke(
                    AsrSentenceResult(
                        text = text.trim(),
                        sentenceId = sentenceCounter,
                        speakerId = speakerId,
                        startTimeMs = startMs,
                        endTimeMs = endMs,
                        isFinal = true
                    )
                )
            } else if (text.isNotBlank()) {
                onInterimResult?.invoke(text.trim())
            } else if (utterances != null && utterances.length() > 0) {
                // text 为空但 utterances 存在时，从第一段 utterance 取文本
                val first = utterances.optJSONObject(0)
                val uttText = first?.optString("text", "") ?: ""
                if (uttText.isNotBlank()) {
                    if (isFinal) {
                        sentenceCounter++
                        val (sid, sMs, eMs) = extractUtteranceMeta(utterances)
                        onSentenceResult?.invoke(
                            AsrSentenceResult(
                                text = uttText.trim(),
                                sentenceId = sentenceCounter,
                                speakerId = sid,
                                startTimeMs = sMs,
                                endTimeMs = eMs,
                                isFinal = true
                            )
                        )
                    } else {
                        onInterimResult?.invoke(uttText.trim())
                    }
                }
            }
        }
    }

    /**
     * 从 utterances 数组中提取第一条的说话人、起止时间。
     * TODO: 时间戳字段名（start_time/end_time）与说话人字段名（speaker）待服务端确认。
     *       当前未确认时返回 "0", 0, 0。
     */
    private fun extractUtteranceMeta(utterances: JSONArray?): Triple<String, Long, Long> {
        if (utterances == null || utterances.length() == 0) {
            return Triple("0", 0L, 0L)
        }
        val first = utterances.optJSONObject(0) ?: return Triple("0", 0L, 0L)
        val speaker = first.optString("speaker", "0")
        val startMs = first.optLong("start_time", 0L)
        val endMs = first.optLong("end_time", 0L)
        return Triple(speaker, startMs, endMs)
    }

    // ═══════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════

    private fun updateState(state: ConnectionState) {
        connectionState = state
        onConnectionStateChanged?.invoke(state)
    }
}
