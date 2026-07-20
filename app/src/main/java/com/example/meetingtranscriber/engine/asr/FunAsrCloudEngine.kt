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
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * FunASR 云端 ASR 引擎（默认云端转写方案）。
 *
 * 通过 WebSocket 连接到 FunASR 云端/自建服务器，发送 PCM 音频，
 * 实时接收 JSON 转写结果。支持普通话、四川话、粤语。
 *
 * 服务端协议:
 * ```
 * Client → ws://host:port/  (二进制 PCM 帧, 16kHz/16bit/mono)
 * Server → JSON  {"text": "...", "is_final": true/false, "mode": "2pass-offline"|"online"}
 * ```
 *
 * 服务端地址在设置中配置 (PreferencesManager.funasrCloudUrl)，
 * 若不配置则不可用。
 */
class FunAsrCloudEngine(
    private val prefs: PreferencesManager
) : AsrEngine {

    override val type: AsrEngineType = AsrEngineType.FUNASR_CLOUD

    @Volatile private var webSocket: WebSocket? = null
    private var sentenceCounter: Long = 0

    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()
    private var appContext: Context? = null
    private var overflowFile: File? = null
    private var overflowOutputStream: FileOutputStream? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
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

        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在检查 FunASR 云端服务...")

        val serverUrl = prefs.funasrCloudUrl
        if (serverUrl.isBlank()) {
            val msg = "FunASR 云端地址未配置（请在设置中填写服务器 URL）"
            Log.w(TAG, msg)
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        _engineStatus.value = EngineStatus(EngineState.READY, "FunASR 云端已就绪")
        Log.i(TAG, "FunASR 云端引擎初始化成功, server=$serverUrl")
        Result.success(Unit)
    }

    override suspend fun start(config: AsrConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val serverUrl = prefs.funasrCloudUrl
        if (serverUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("FunASR 云端地址未配置"))
        }

        try {
            sentenceCounter = 0
            _interimText.value = ""
            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在连接 FunASR 云端...")

            reconnectHandler.reset()
            openWebSocket(serverUrl, config)
            _engineStatus.value = EngineStatus(EngineState.RUNNING)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "启动 FunASR 云端失败", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "启动失败: ${e.message}")
            Result.failure(e)
        }
    }

    override fun processAudio(pcmData: ByteArray) {
        if (_engineStatus.value.state != EngineState.RUNNING) return

        if (webSocket != null) {
            try {
                webSocket?.send(ByteString.of(*pcmData))
            } catch (e: Exception) {
                Log.w(TAG, "发送音频帧失败: ${e.message}")
            }
        } else {
            // 缓冲直到 WebSocket 就绪
            if (audioBuffer.size < EngineConstants.MAX_BUFFER_FRAMES) {
                audioBuffer.offer(pcmData.copyOf())
            } else {
                writeToOverflowFile(pcmData)
            }
        }
    }

    override suspend fun finalize() {
        try {
            // FunASR 协议：发送文本结束标记
            webSocket?.send("{ \"is_speaking\": false }")
            webSocket?.close(1000, "音频结束")
        } catch (_: Exception) {}
        webSocket = null
        audioBuffer.clear()
        cleanupOverflowFile()
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.READY, "转写已结束")
        Log.i(TAG, "FunASR 云端 finalize 完成, 共 $sentenceCounter 句")
    }

    override suspend fun dispose() {
        reconnectHandler.cancel()
        reconnectUrl = null
        reconnectConfig = null
        try {
            webSocket?.close(1000, "引擎释放")
        } catch (_: Exception) {}
        webSocket = null
        audioBuffer.clear()
        cleanupOverflowFile()
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        Log.i(TAG, "FunASR 云端引擎已释放")
    }

    // ═══════════════════════════════════════════════════════════
    // WebSocket 管理
    // ═══════════════════════════════════════════════════════════

    private val reconnectHandler = ReconnectHandler(TAG)
    @Volatile private var reconnectConfig: AsrConfig? = null
    @Volatile private var reconnectUrl: String? = null

    private fun openWebSocket(serverUrl: String, config: AsrConfig) {
        reconnectUrl = serverUrl
        reconnectConfig = config
        reconnectHandler.reset()

        doConnect(serverUrl, config)
    }

    private fun doConnect(serverUrl: String, config: AsrConfig) {
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        Log.i(TAG, "连接 FunASR 云端: $serverUrl, language=${config.language}")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "FunASR 云端 WebSocket 已连接")
                reconnectHandler.reset()
                // 发送初始配置（可选：部分 FunASR 服务端支持）
                val initMsg = JSONObject().apply {
                    put("mode", "2pass")
                    put("chunk_size", intArrayOf(5, 10, 5))
                    put("wav_name", "meeting_stream")
                    put("is_speaking", true)
                }
                webSocket.send(initMsg.toString())
                flushBuffer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "收到消息: ${text.take(300)}")
                try {
                    parseResult(JSONObject(text))
                } catch (e: Exception) {
                    Log.w(TAG, "解析消息失败: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // FunASR 云端通常返回 JSON 文本，但也兼容二进制
                try {
                    val text = bytes.utf8()
                    if (text.trimStart().startsWith("{")) {
                        parseResult(JSONObject(text))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析二进制消息失败: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
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
                Log.e(TAG, "FunASR 云端连接失败: ${t.message}")
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
        val url = reconnectUrl ?: return
        val config = reconnectConfig ?: return

        if (reconnectHandler.isExhausted) {
            Log.e(TAG, "已达最大重连次数，放弃重连")
            audioBuffer.clear()
            _engineStatus.value = EngineStatus(EngineState.ERROR, "连接失败，已重试 ${EngineConstants.MAX_RECONNECT_ATTEMPTS} 次")
            return
        }

        reconnectHandler.start { attempt ->
            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在重连 ($attempt/${EngineConstants.MAX_RECONNECT_ATTEMPTS})...")
            doConnect(url, config)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 结果解析
    // ═══════════════════════════════════════════════════════════

    /**
     * 解析 FunASR WebSocket 服务端返回的 JSON。
     *
     * 标准格式:
     * ```json
     * {"text": "转写文本", "is_final": false, "mode": "2pass-online"}
     * {"text": "完整句子。", "is_final": true, "mode": "2pass-offline",
     *  "timestamp": "[[100,200],...]", "wav_name": "..."}
     * ```
     */
    private fun parseResult(json: JSONObject) {
        // stamp 帧（时间戳信息，非文本）
        if (json.has("stamp")) return

        val text = json.optString("text", "")
        if (text.isBlank()) return

        val isFinal = json.optBoolean("is_final", false)

        if (isFinal) {
            sentenceCounter++
            // 尝试提取时间戳
            val timestamp = json.optJSONArray("timestamp")
            val startMs = timestamp
                ?.optJSONArray(0)
                ?.optLong(0, 0L) ?: 0L
            val endMs = timestamp
                ?.optJSONArray(0)
                ?.let { it.optLong(it.length() - 1, 0L) } ?: 0L

            _sentenceResults.tryEmit(
                AsrSentence(
                    text = text.trim(),
                    sentenceId = sentenceCounter,
                    speakerId = "speaker_0",
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    isFinal = true
                )
            )
            _interimText.value = ""
            Log.d(TAG, "句子 #$sentenceCounter: ${text.trim().take(50)}...")
        } else {
            _interimText.value = text.trim()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 缓冲管理
    // ═══════════════════════════════════════════════════════════

    private fun flushBuffer() {
        var sent = 0
        while (audioBuffer.isNotEmpty()) {
            val data = audioBuffer.poll() ?: break
            try {
                webSocket?.send(ByteString.of(*data))
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
                        try { webSocket?.send(ByteString.of(*chunk)); sent++ }
                        catch (e: Exception) { Log.w(TAG, "溢出帧发送失败: ${e.message}"); break }
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
                overflowFile = File(appContext?.cacheDir, "funasr_cloud_overflow_${System.currentTimeMillis()}.pcm")
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

    companion object {
        private const val TAG = "FunAsrCloudEngine"
    }
}
