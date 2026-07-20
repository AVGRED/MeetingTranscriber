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
import okhttp3.*
import okio.ByteString
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * 云端实时 ASR 厂家描述：凭证字段（UI 动态生成配置卡片）与获取地址。
 * 各家协议互不兼容，引擎实现见 [CloudAsrWsEngine] 的四个子类。
 */
enum class CloudAsrProvider(
    val type: AsrEngineType,
    /** 凭证字段：(输入框提示, 是否密文显示)，最多 3 个 */
    val fields: List<Pair<String, Boolean>>,
    /** API Key 获取地址（接入说明用） */
    val keyUrl: String,
    /** 接入说明补充 */
    val note: String
) {
    PARAFORMER(
        type = AsrEngineType.PARAFORMER_CLOUD,
        fields = listOf("API Key（留空则复用通义千问 DashScope 的 Key）" to true),
        keyUrl = "https://dashscope.console.aliyun.com/apiKey",
        note = "与通义千问共用 DashScope API Key：LLM 里配过就无需再填"
    ),
    XFYUN(
        type = AsrEngineType.XFYUN_CLOUD,
        fields = listOf("AppID" to false, "APIKey（实时语音转写专用）" to true),
        keyUrl = "https://console.xfyun.cn/services/rta",
        note = "控制台需开通「实时语音转写」服务（有免费时长）"
    ),
    TENCENT(
        type = AsrEngineType.TENCENT_CLOUD,
        fields = listOf("AppID" to false, "SecretId" to false, "SecretKey" to true),
        keyUrl = "https://console.cloud.tencent.com/cam/capi",
        note = "AppID 在账号中心查看；需开通「语音识别」服务"
    ),
    BAIDU(
        type = AsrEngineType.BAIDU_CLOUD,
        fields = listOf("AppID" to false, "API Key" to true),
        keyUrl = "https://console.bce.baidu.com/ai/#/ai/speech/app/list",
        note = "创建语音技术应用后获得；需开通「实时语音识别」"
    );

    /** 读取指定凭证槽位；Paraformer 空槽回落到 DashScope Key（与 LLM 共用） */
    fun credential(prefs: PreferencesManager, slot: Int): String {
        val v = prefs.getAsrCred(type, slot)
        if (this == PARAFORMER && slot == 0 && v.isBlank()) return prefs.dashScopeApiKey
        return v
    }

    /** 全部必填凭证是否已配置 */
    fun hasKeys(prefs: PreferencesManager): Boolean =
        fields.indices.all { credential(prefs, it).isNotBlank() }

    companion object {
        fun of(type: AsrEngineType): CloudAsrProvider? = entries.find { it.type == type }
    }
}

/**
 * 云端实时 ASR WebSocket 引擎基类。
 *
 * 阿里 Paraformer / 讯飞 / 腾讯云 / 百度四家协议虽互不兼容，但共享同一骨架：
 * WebSocket 连接 + 二进制 PCM 直发 + JSON 文本结果。基类承包连接生命周期、
 * 就绪前音频缓冲（内存队列，容量 2 分钟）、断线重连与状态流；子类只实现
 * 四个协议点：建连请求（鉴权）、握手帧、结果解析、结束帧。
 */
abstract class CloudAsrWsEngine(
    protected val prefs: PreferencesManager
) : AsrEngine {

    protected abstract val tag: String
    protected abstract val provider: CloudAsrProvider
    override val type: AsrEngineType get() = provider.type

    // ── 协议点（子类实现） ──

    /** 构造带鉴权的 WebSocket 建连请求 */
    protected abstract fun buildRequest(config: AsrConfig): Request

    /** 连接建立后的握手（发送开始帧等）。就绪后子类须调 [markReadyForAudio]——
     *  立即就绪的协议直接在本方法里调，需等服务端确认的在 [handleTextMessage] 里调 */
    protected abstract fun onWsOpen(ws: WebSocket, config: AsrConfig)

    /** 解析服务端文本消息（结果/错误/握手确认） */
    protected abstract fun handleTextMessage(text: String)

    /** 音频发送完毕的结束帧 */
    protected abstract fun sendFinishFrame(ws: WebSocket)

    // ── 内部状态 ──

    @Volatile protected var webSocket: WebSocket? = null
    @Volatile private var readyForAudio = false
    protected var sentenceCounter = 0L

    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()

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
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return@withContext Result.success(Unit)
        }
        if (!provider.hasKeys(prefs)) {
            val msg = "${type.displayName} 密钥未配置"
            Log.w(tag, msg)
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        _engineStatus.value = EngineStatus(EngineState.READY, "${type.displayName} 已就绪")
        Log.i(tag, "${type.displayName} 引擎初始化成功")
        Result.success(Unit)
    }

    override suspend fun start(config: AsrConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sentenceCounter = 0
            readyForAudio = false
            _interimText.value = ""
            reconnectAttempts = 0
            reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            reconnectConfig = config

            _engineStatus.value = EngineStatus(EngineState.LOADING, "正在连接${type.displayName}...")
            openWebSocket(config)
            _engineStatus.value = EngineStatus(EngineState.RUNNING)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "启动失败", e)
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
                Log.w(tag, "发送音频帧失败: ${e.message}")
            }
        } else {
            // 未就绪 → 内存缓冲（100ms/帧 × 1200 ≈ 2 分钟，超出丢最旧）
            if (audioBuffer.size >= EngineConstants.MAX_BUFFER_FRAMES) audioBuffer.poll()
            audioBuffer.offer(pcmData.copyOf())
        }
    }

    override suspend fun finalize() {
        try {
            webSocket?.let { sendFinishFrame(it) }
        } catch (_: Exception) {}
        // 给服务端一点时间吐最后的结果帧再关闭（各家 final 均在结束帧后返回）
        try { delay(800) } catch (_: Exception) {}
        try { webSocket?.close(1000, "用户结束会议") } catch (_: Exception) {}
        webSocket = null
        readyForAudio = false
        audioBuffer.clear()
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.READY, "转写已结束")
        Log.i(tag, "finalize 完成，共 $sentenceCounter 句")
    }

    override suspend fun dispose() {
        reconnectAttempts = EngineConstants.MAX_RECONNECT_ATTEMPTS
        reconnectConfig = null
        reconnectScope?.cancel()
        reconnectScope = null
        try { webSocket?.close(1000, "引擎释放") } catch (_: Exception) {}
        webSocket = null
        readyForAudio = false
        audioBuffer.clear()
        _interimText.value = ""
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        Log.i(tag, "${type.displayName} 引擎已释放")
    }

    // ═══════════════════════════════════════════════════════════
    // 子类工具
    // ═══════════════════════════════════════════════════════════

    /** 握手完成，开始发音频并重放缓冲 */
    protected fun markReadyForAudio() {
        readyForAudio = true
        var sent = 0
        while (audioBuffer.isNotEmpty()) {
            val data = audioBuffer.poll() ?: break
            try {
                webSocket?.send(ByteString.of(*data))
                sent++
            } catch (e: Exception) {
                Log.w(tag, "缓冲帧发送失败: ${e.message}")
                break
            }
        }
        if (sent > 0) Log.i(tag, "已重放 $sent 帧缓冲音频")
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
                isFinal = true
            )
        )
        _interimText.value = ""
    }

    protected fun setInterim(text: String) {
        if (text.isNotBlank()) _interimText.value = text.trim()
    }

    protected fun reportError(msg: String) {
        Log.e(tag, "服务端错误: $msg")
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
        Log.i(tag, "打开 WebSocket: ${request.url.host}")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "WebSocket 已连接, status=${response.code}")
                reconnectAttempts = 0
                try {
                    onWsOpen(webSocket, config)
                } catch (e: Exception) {
                    Log.e(tag, "握手失败: ${e.message}")
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "握手失败: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleTextMessage(text)
                } catch (e: Exception) {
                    Log.w(tag, "解析消息失败: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 四家结果均为文本帧；个别网关可能以二进制发 JSON，尝试按 UTF-8 解析
                try {
                    handleTextMessage(bytes.utf8())
                } catch (e: Exception) {
                    Log.w(tag, "解析二进制消息失败: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WebSocket 关闭中: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WebSocket 已关闭: $code $reason")
                if (code != 1000 && _engineStatus.value.state == EngineState.RUNNING) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket 失败: ${t.message}, response=${response?.code}")
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
        readyForAudio = false
        if (reconnectAttempts >= EngineConstants.MAX_RECONNECT_ATTEMPTS) {
            Log.e(tag, "已达最大重连次数 (${EngineConstants.MAX_RECONNECT_ATTEMPTS})，放弃重连")
            audioBuffer.clear()
            _engineStatus.value = EngineStatus(EngineState.ERROR, "连接失败，已重试 $reconnectAttempts 次")
            return
        }
        reconnectAttempts++
        val delay = (EngineConstants.RECONNECT_BASE_DELAY_MS * reconnectAttempts)
            .coerceAtMost(EngineConstants.RECONNECT_MAX_DELAY_MS)
        Log.i(tag, "将在 ${delay}ms 后尝试第 $reconnectAttempts 次重连...")
        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在重连 ($reconnectAttempts/${EngineConstants.MAX_RECONNECT_ATTEMPTS})...")
        val config = reconnectConfig ?: return
        reconnectScope?.launch {
            delay(delay)
            openWebSocket(config)
        }
    }

    companion object {
        // 常量已迁移至 EngineConstants — 保留引用以保持兼容
    }
}
