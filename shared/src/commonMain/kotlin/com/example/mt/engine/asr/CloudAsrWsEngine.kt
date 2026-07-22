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
import java.util.concurrent.TimeUnit

/**
 * 云端实时 ASR WebSocket 引擎基类（跨平台，无 Android 依赖）。
 *
 * 阿里 Paraformer / 讯飞 / 腾讯云 / 百度四家协议虽互不兼容，但共享同一骨架：
 * WebSocket 连接 + 二进制 PCM 直发 + JSON 文本结果。基类承包连接生命周期、
 * 就绪前音频缓冲（内存队列，容量 2 分钟）、断线重连与状态流；子类只实现
 * 四个协议点：建连请求（鉴权）、握手帧、结果解析、结束帧。
 */
abstract class CloudAsrWsEngine(
    protected val keys: EngineKeys,
) : AsrEngine {

    protected abstract val tag: String
    protected abstract val provider: CloudAsrProvider
    override val type: AsrEngineType get() = provider.type

    // ── 协议点（子类实现） ──

    /** 构造带鉴权的 WebSocket 建连请求 */
    protected abstract fun buildRequest(config: AsrConfig): Request

    /** 连接建立后的握手（发送开始帧等）。就绪后子类须调 [markReadyForAudio] */
    protected abstract fun onWsOpen(ws: WebSocket, config: AsrConfig)

    /** 解析服务端文本消息（结果/错误/握手确认） */
    protected abstract fun handleTextMessage(text: String)

    /** 音频发送完毕的结束帧 */
    protected abstract fun sendFinishFrame(ws: WebSocket)

    // ── 内部状态 ──

    @Volatile protected var webSocket: WebSocket? = null
    @Volatile private var readyForAudio = false
    protected var sentenceCounter = 0L

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

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Default) {
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return@withContext Result.success(Unit)
        }
        if (!provider.hasKeys(keys)) {
            val msg = "${type.displayName} 密钥未配置"
            Napier.w("$tag: $msg")
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        _engineStatus.value = EngineStatus(EngineState.READY, "${type.displayName} 已就绪")
        Napier.i("$tag: ${type.displayName} 引擎初始化成功")
        Result.success(Unit)
    }

    override suspend fun start(config: AsrConfig): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            sentenceCounter = 0
            readyForAudio = false
            _interimText.value = ""
            reconnectAttempts = 0
            reconnectScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            reconnectConfig = config

            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在连接${type.displayName}...")
            openWebSocket(config)
            _engineStatus.value = EngineStatus(EngineState.RUNNING)
            Result.success(Unit)
        } catch (e: Exception) {
            Napier.e("$tag: 启动失败", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "启动失败: ${e.message}")
            Result.failure(e)
        }
    }

    override fun processAudio(pcmData: ByteArray) {
        if (_engineStatus.value.state != EngineState.RUNNING) return
        val ws = webSocket
        if (ws != null && readyForAudio) {
            try {
                ws.send(ByteString.of(*pcmData))
            } catch (e: Exception) {
                Napier.w("$tag: 发送音频帧失败: ${e.message}")
            }
        } else {
            // 未就绪 → 缓冲（Channel 满则丢弃最旧的）
            audioBuffer.trySend(pcmData.copyOf())
        }
    }

    override suspend fun finalize() {
        try {
            webSocket?.let { sendFinishFrame(it) }
        } catch (_: Exception) {}
        try { delay(800) } catch (_: Exception) {}
        try { webSocket?.close(1000, "用户结束会议") } catch (_: Exception) {}
        webSocket = null
        readyForAudio = false
        // 清空缓冲
        while (audioBuffer.tryReceive().isSuccess) { /* drain */ }
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.READY, "转写已结束")
        Napier.i("$tag: finalize 完成，共 $sentenceCounter 句")
    }

    override suspend fun dispose() {
        reconnectAttempts = EngineConstants.MAX_RECONNECT_ATTEMPTS
        reconnectConfig = null
        reconnectScope?.cancel()
        reconnectScope = null
        try { webSocket?.close(1000, "引擎释放") } catch (_: Exception) {}
        webSocket = null
        readyForAudio = false
        while (audioBuffer.tryReceive().isSuccess) { /* drain */ }
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        Napier.i("$tag: ${type.displayName} 引擎已释放")
    }

    // ═══════════════════════════════════════════════════════════
    // 子类工具
    // ═══════════════════════════════════════════════════════════

    protected fun markReadyForAudio() {
        readyForAudio = true
        var sent = 0
        while (true) {
            val data = audioBuffer.tryReceive().getOrNull() ?: break
            try {
                webSocket?.send(ByteString.of(*data))
                sent++
            } catch (e: Exception) {
                Napier.w("$tag: 缓冲帧发送失败: ${e.message}")
                break
            }
        }
        if (sent > 0) Napier.i("$tag: 已重放 $sent 帧缓冲音频")
    }

    protected fun emitSentence(text: String, speakerId: String = "0", startMs: Long = 0, endMs: Long = 0) {
        if (text.isBlank()) return
        sentenceCounter++
        _sentenceResults.tryEmit(
            AsrSentence(
                text = text.trim(),
                sentenceId = sentenceCounter,
                speakerId = speakerId,
                startTimeMs = startMs,
                endTimeMs = endMs,
                isFinal = true,
            )
        )
        _interimText.value = ""
    }

    protected fun setInterim(text: String) {
        if (text.isNotBlank()) _interimText.value = text.trim()
    }

    protected fun reportError(msg: String) {
        Napier.e("$tag: 服务端错误: $msg")
        _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
    }

    // ═══════════════════════════════════════════════════════════
    // WebSocket 连接 + 重连
    // ═══════════════════════════════════════════════════════════

    @Volatile private var reconnectAttempts = 0
    private var reconnectScope: CoroutineScope? = null
    private var reconnectConfig: AsrConfig? = null

    private fun openWebSocket(config: AsrConfig) {
        readyForAudio = false
        val request = buildRequest(config)
        Napier.i("$tag: 打开 WebSocket: ${request.url.host}")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Napier.i("$tag: WebSocket 已连接, status=${response.code}")
                reconnectAttempts = 0
                try {
                    onWsOpen(webSocket, config)
                } catch (e: Exception) {
                    Napier.e("$tag: 握手失败: ${e.message}")
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "握手失败: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleTextMessage(text)
                } catch (e: Exception) {
                    Napier.w("$tag: 解析消息失败: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    handleTextMessage(bytes.utf8())
                } catch (e: Exception) {
                    Napier.w("$tag: 解析二进制消息失败: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Napier.i("$tag: WebSocket 关闭中: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Napier.i("$tag: WebSocket 已关闭: $code $reason")
                if (code != 1000 && _engineStatus.value.state == EngineState.RUNNING) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Napier.e("$tag: WebSocket 失败: ${t.message}, response=${response?.code}")
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
        readyForAudio = false
        if (reconnectAttempts >= EngineConstants.MAX_RECONNECT_ATTEMPTS) {
            Napier.e("$tag: 已达最大重连次数 (${EngineConstants.MAX_RECONNECT_ATTEMPTS})，放弃重连")
            while (audioBuffer.tryReceive().isSuccess) { /* drain */ }
            _engineStatus.value = EngineStatus(EngineState.ERROR, "连接失败，已重试 $reconnectAttempts 次")
            return
        }
        reconnectAttempts++
        val delay = (EngineConstants.RECONNECT_BASE_DELAY_MS * reconnectAttempts)
            .coerceAtMost(EngineConstants.RECONNECT_MAX_DELAY_MS)
        Napier.i("$tag: 将在 ${delay}ms 后尝试第 $reconnectAttempts 次重连...")
        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在重连 ($reconnectAttempts/${EngineConstants.MAX_RECONNECT_ATTEMPTS})...")
        val config = reconnectConfig ?: return
        reconnectScope?.launch {
            kotlinx.coroutines.delay(delay)
            openWebSocket(config)
        }
    }
}
