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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 豆包/火山引擎云端 ASR 引擎。
 *
 * 基于官方 WebSocket 二进制协议（docs/6561/1354869）:
 * - URL: bigmodel_async（双向流式优化版）
 * - 新控制台鉴权: X-Api-Key
 * - 帧头: 4 字节 nibble 位域 + 4 字节 payload size
 */
class VolcengineEngine(
    private val prefs: PreferencesManager
) : AsrEngine {

    override val type: AsrEngineType = AsrEngineType.VOLCENGINE_CLOUD

    // ── 内部状态 ──
    @Volatile private var webSocket: WebSocket? = null
    private var connectId: String = ""
    private var sentenceCounter: Long = 0
    private var audioSeq: Int = 0
    private val webSocketReady = AtomicBoolean(false)

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
            Log.e(TAG, "启动豆包 ASR 失败", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "启动失败: ${e.message}")
            Result.failure(e)
        }
    }

    override fun processAudio(pcmData: ByteArray) {
        if (_engineStatus.value.state != EngineState.RUNNING) return

        // 仅当 WebSocket 存在且握手完成（onOpen 已触发）后才直接发送
        // 否则缓冲，等 onOpen → sendFullClientRequest → flushBuffer 统一发送
        // 避免音频帧在 FullClientRequest 之前到达服务端导致 "no request object before data"
        if (webSocket != null && webSocketReady.get()) {
            try {
                val compressed = gzipCompress(pcmData)
                audioSeq++
                val frame = buildAudioFrame(compressed, audioSeq, last = false)
                webSocket?.send(ByteString.of(*frame))
            } catch (e: Exception) {
                Log.w(TAG, "发送音频帧失败: ${e.message}")
            }
        } else {
            if (audioBuffer.size < EngineConstants.MAX_BUFFER_FRAMES) {
                audioBuffer.offer(pcmData.copyOf())
            } else {
                writeToOverflowFile(pcmData)
            }
        }
    }

    override suspend fun finalize() {
        webSocketReady.set(false)
        try {
            // 发送空 payload 最后一帧（不压缩，否则空 Gzip 块服务端解压 EOF）
            val lastFrame = buildClientFrame(MSG_AUDIO_ONLY, FLAG_LAST, SER_NONE, COMP_NONE, ByteArray(0))
            webSocket?.send(ByteString.of(*lastFrame))
            Log.i(TAG, "已发送音频结束帧")
            // 给服务端一点时间返回最终结果
            delay(1500)
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
        webSocketReady.set(false)
        reconnectHandler.cancel()
        reconnectConfig = null
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

    private val reconnectHandler = ReconnectHandler(TAG)
    @Volatile private var reconnectConfig: AsrConfig? = null

    private fun openWebSocket(config: AsrConfig) {
        val languageCode = if (config.language == "cn") "zh-CN" else config.language
        val requestId = UUID.randomUUID().toString()

        val request = Request.Builder()
            .url(WS_URL)
            .apply {
                // 新控制台: X-Api-Key
                // 旧控制台: X-Api-Access-Key (fallback)
                val apiKey = prefs.volcengineAsrApiKey
                val accessToken = prefs.volcengineAsrAccessToken

                // 新版控制台 App Key
                header("X-Api-Key", apiKey)
                // 同时也可能是旧版 App ID
                header("X-Api-App-Key", apiKey)
                Log.i(TAG, "鉴权: X-Api-Key + X-Api-App-Key")
            }
            .header("X-Api-Resource-Id", RESOURCE_ID)
            .header("X-Api-Request-Id", requestId)
            .header("X-Api-Connect-Id", connectId)
            .build()

        Log.i(TAG, "打开 WebSocket: resource=$RESOURCE_ID, requestId=$requestId")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接, status=${response.code}")
                // 记录服务端返回的 logid 方便排查
                val logId = response.header("X-Tt-Logid") ?: "N/A"
                Log.i(TAG, "X-Tt-Logid=$logId")
                reconnectHandler.reset()
                webSocketReady.set(true)
                sendFullClientRequest(languageCode)
                flushBuffer()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    parseBinaryMessage(bytes.toByteArray())
                } catch (e: Exception) {
                    Log.w(TAG, "解析二进制消息失败: ${e.message}", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "收到文本消息: ${text.take(200)}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 关闭中: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 已关闭: $code $reason")
                webSocketReady.set(false)
                if (code != 1000 && _engineStatus.value.state == EngineState.RUNNING) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 失败: ${t.message}, response=${response?.code}")
                webSocketReady.set(false)
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
        val config = reconnectConfig ?: return

        if (reconnectHandler.isExhausted) {
            Log.e(TAG, "已达最大重连次数，放弃重连")
            audioBuffer.clear()
            _engineStatus.value = EngineStatus(EngineState.ERROR,
                "连接失败，已重试 ${EngineConstants.MAX_RECONNECT_ATTEMPTS} 次")
            return
        }

        webSocketReady.set(false) // 重连期间禁止直接发送，音频回缓冲
        reconnectHandler.start { attempt ->
            audioSeq = 0
            _engineStatus.value = EngineStatus(EngineState.LOADING,
                "正在重连 ($attempt/${EngineConstants.MAX_RECONNECT_ATTEMPTS})...")
            openWebSocket(config)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 二进制帧构建 — 基于官方 header 格式（4 字节 nibble 位域）
    // ═══════════════════════════════════════════════════════════

    /**
     * 构建 4 字节帧头。
     *   Byte 0: [protocol_version:4][header_size:4] = 0x11
     *   Byte 1: [message_type:4][flags:4]
     *   Byte 2: [serialization:4][compression:4]
     *   Byte 3: reserved = 0x00
     */
    private fun buildHeader(msgType: Int, flags: Int, serialization: Int, compression: Int): ByteArray {
        return byteArrayOf(
            ((PROTOCOL_VERSION shl 4) or HEADER_SIZE).toByte(),   // 0x11
            ((msgType shl 4) or (flags and 0x0F)).toByte(),
            ((serialization shl 4) or (compression and 0x0F)).toByte(),
            0x00.toByte()
        )
    }

    /** int32 大端 → 4 字节 */
    private fun int32BE(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    /** 从字节数组读取大端 int32 */
    private fun readInt32BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    /**
     * 构建客户端请求帧: header(4B) + payload_size(4B) + payload
     * （客户端请求无 sequence 字段，服务端响应才有）
     */
    private fun buildClientFrame(
        msgType: Int, flags: Int, serialization: Int, compression: Int, payload: ByteArray
    ): ByteArray {
        val header = buildHeader(msgType, flags, serialization, compression)
        val sizeBytes = int32BE(payload.size)
        return header + sizeBytes + payload
    }

    /**
     * 构建音频帧: header(4B) + payload_size(4B) + payload
     * V1 协议中客户端请求不包含 sequence，由服务端自动分配。
     */
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
                    put("enable_punc", true)
                    put("enable_itn", true)
                    put("enable_ddc", true)
                    put("show_utterances", true)
                    put("result_type", "single")
                    // 说话人分离：服务端返回 words[].speaker，按人标注
                    put("enable_speaker_info", true)
                    // 二遍识别: 流式实时出字 + 非流式修正，兼顾速度与准确率
                    put("enable_nonstream", true)
                    put("end_window_size", 800)
                })
            }

            val jsonBytes = requestJson.toString().toByteArray(Charsets.UTF_8)
            val compressed = gzipCompress(jsonBytes)

            val frame = buildClientFrame(
                MSG_FULL_REQUEST, FLAG_NONE, SER_JSON, COMP_GZIP, compressed
            )

            webSocket?.send(ByteString.of(*frame))
            Log.i(TAG, "已发送完整客户端请求 (${jsonBytes.size}B → ${compressed.size}B Gzip)")
        } catch (e: Exception) {
            Log.e(TAG, "发送初始请求失败: ${e.message}", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "发送请求失败: ${e.message}")
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
                val compressed = gzipCompress(data)
                audioSeq++
                val frame = buildAudioFrame(compressed, audioSeq, last = false)
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
                    val buffer = ByteArray(EngineConstants.AUDIO_FRAME_SIZE)
                    while (true) {
                        val read = fis.read(buffer)
                        if (read <= 0) break
                        val chunk = if (read == EngineConstants.AUDIO_FRAME_SIZE) buffer.copyOf()
                        else buffer.copyOfRange(0, read)
                        try {
                            val compressed = gzipCompress(chunk)
                            audioSeq++
                            val frame = buildAudioFrame(compressed, audioSeq, last = false)
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

    /**
     * 解析服务端二进制帧。
     *
     * 服务端响应格式: header(4B) + sequence(4B) + payload_size(4B) + payload
     * 错误帧格式:      header(4B) + error_code(4B) + error_size(4B) + error_msg
     */
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
                Log.d(TAG, "响应 [seq=$sequence, flags=$flags]: ${jsonString.take(300)}")
                try {
                    parseResponseJson(JSONObject(jsonString), flags)
                } catch (e: Exception) {
                    Log.w(TAG, "解析响应 JSON 失败: ${e.message}")
                }
            }
            MSG_SERVER_ERROR -> {
                if (data.size < 12) return
                val errorCode = readInt32BE(data, 4)
                val errorSize = readInt32BE(data, 8)
                if (data.size < 12 + errorSize) return
                val errorMsg = String(data.copyOfRange(12, 12 + errorSize), Charsets.UTF_8)
                Log.e(TAG, "服务端错误 [$errorCode]: $errorMsg")
                _engineStatus.value = EngineStatus(EngineState.ERROR, "服务端错误: $errorMsg")
            }
            else -> {
                Log.w(TAG, "未知消息类型: $msgType")
            }
        }
    }

    /**
     * 解析识别结果 JSON。
     *
     * 官方格式:
     * ```
     * {
     *   "audio_info": {"duration": 10000},
     *   "result": {
     *     "text": "全文累计识别文本",
     *     "utterances": [
     *       {"text": "分句文本", "definite": true, "start_time": 0, "end_time": 1705, "words": [...]},
     *       ...
     *     ]
     *   }
     * }
     * ```
     */
    private fun parseResponseJson(json: JSONObject, flags: Int) {
        // 优先解析 audio_info（可能独立出现）
        val audioInfo = json.optJSONObject("audio_info")

        val result = json.optJSONObject("result") ?: run {
            // 有些响应只含 audio_info，不视为错误
            if (audioInfo != null) {
                Log.d(TAG, "仅含 audio_info: duration=${audioInfo.optInt("duration")}")
            }
            return
        }

        val utterances = result.optJSONArray("utterances")
        val fullText = result.optString("text", "")

        if (utterances != null && utterances.length() > 0) {
            // 逐 utterance 处理
            for (i in 0 until utterances.length()) {
                val utt = utterances.optJSONObject(i) ?: continue
                val uttText = utt.optString("text", "")
                val definite = utt.optBoolean("definite", false)
                val startMs = utt.optLong("start_time", 0L)
                val endMs = utt.optLong("end_time", 0L)

                if (uttText.isBlank()) continue

                if (definite) {
                    // definite=true → 一个完整分句（VAD 判停或 end_window_size 触发）
                    sentenceCounter++
                    _sentenceResults.tryEmit(
                        AsrSentence(
                            text = uttText.trim(),
                            sentenceId = sentenceCounter,
                            speakerId = extractSpeakerId(utt),
                            startTimeMs = startMs,
                            endTimeMs = endMs,
                            isFinal = true
                        )
                    )
                    Log.d(TAG, "分句: \"${uttText.trim().take(30)}...\"")
                } else {
                    // 非 definite → 中间结果
                    _interimText.value = uttText.trim()
                }
            }
        }

        // 全文也更新 interim（definite 的 utterance 已经 emit，这里做兜底）
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
                        isFinal = true
                    )
                )
            } else {
                _interimText.value = fullText.trim()
            }
        }
    }

    /** 从 utterance 的 words 中提取说话人信息（如果 speaker_info 未开启则返回 "0"） */
    private fun extractSpeakerId(utt: JSONObject): String {
        val words = utt.optJSONArray("words") ?: return "0"
        // 开启 enable_speaker_info 后，words 中可能有 speaker 字段
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

        // ── 端点 ──
        /** 双向流式优化版（推荐）: 仅结果变化时返回，RTF 和延迟最优 */
        private const val WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        private const val RESOURCE_ID = "volc.seedasr.sauc.duration"

        // ── Header 位域 ──
        private const val PROTOCOL_VERSION = 1       // 0b0001
        private const val HEADER_SIZE = 1            // 0b0001 → 1×4 = 4 bytes

        // ── 消息类型 (4 bits, Byte 1 高半字节) ──
        private const val MSG_FULL_REQUEST = 0x1     // 完整客户端请求
        private const val MSG_AUDIO_ONLY = 0x2       // 音频帧
        private const val MSG_SERVER_RESPONSE = 0x9  // 服务端识别结果
        private const val MSG_SERVER_ERROR = 0xF     // 服务端错误

        // ── 标志位 (4 bits, Byte 1 低半字节) ──
        private const val FLAG_NONE = 0x0            // 无特殊含义
        private const val FLAG_POS_SEQ = 0x1         // 后跟正数 sequence
        private const val FLAG_LAST = 0x2            // 最后一包（无 sequence）
        private const val FLAG_SERVER_FINAL = 0x3    // 服务端最终结果

        // ── 序列化方式 (4 bits, Byte 2 高半字节) ──
        private const val SER_NONE = 0x0             // 无序列化（原始字节）
        private const val SER_JSON = 0x1             // JSON

        // ── 压缩方式 (4 bits, Byte 2 低半字节) ──
        private const val COMP_NONE = 0x0            // 不压缩
        private const val COMP_GZIP = 0x1            // Gzip
    }
}
