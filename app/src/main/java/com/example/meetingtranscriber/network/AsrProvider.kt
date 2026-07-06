package com.example.meetingtranscriber.network

/**
 * ASR（自动语音识别）Provider 抽象接口。
 *
 * 定义实时语音转写的统一操作和回调，使上层（MeetingViewModel）不依赖具体云端实现。
 * 当前实现：[TingwuAsrProvider]（通义听悟），后续可接入 Doubao/Volcengine 等。
 */
interface AsrProvider {

    // ── 回调 ──

    /** 实时中间结果（逐词/逐字出字） */
    var onInterimResult: ((String) -> Unit)?

    /** 完整句子结果（含说话人、时间戳） */
    var onSentenceResult: ((AsrSentenceResult) -> Unit)?

    /** 连接状态变化 */
    var onConnectionStateChanged: ((ConnectionState) -> Unit)?

    /** 错误回调 */
    var onError: ((String) -> Unit)?

    // ── 操作 ──

    /**
     * 启动 ASR 连接（创建任务 + 建立 WebSocket）。
     * @return true 表示成功，false 表示失败
     */
    suspend fun start(config: AsrConfig): Boolean

    /** 发送 PCM 音频帧（16kHz/16bit/mono） */
    fun sendAudio(pcmData: ByteArray)

    /** 断开 WebSocket 连接，清理缓冲 */
    fun disconnect()

    /** 停止云端转写任务（REST API 调用） */
    suspend fun stopTask()

    // ── 状态查询（崩溃恢复用） ──

    /** 当前转写任务 ID（用于崩溃恢复），无则返回空字符串 */
    fun getCurrentTaskId(): String

    /** 溢出文件路径（内存缓冲满时使用），无则返回 null */
    fun getOverflowFilePath(): String?

    /** 当前内存缓冲中的音频帧数 */
    fun getBufferSize(): Int
}

/**
 * ASR 连接配置。
 */
data class AsrConfig(
    /** 源语言代码，如 "cn" */
    val language: String = "cn",
    /** 可选的热词/词汇表 ID */
    val vocabularyId: String? = null
)

/**
 * WebSocket / 云端连接状态。
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * ASR 返回的完整句子结果。
 */
data class AsrSentenceResult(
    val text: String,
    val sentenceId: Long,
    val speakerId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val isFinal: Boolean = true
)

// ═══════════════════════════════════════════════════════════════
// Provider 类型 & 工厂
// ═══════════════════════════════════════════════════════════════

/**
 * ASR 提供商类型，供用户在设置中选择。
 */
enum class AsrProviderType(val displayName: String) {
    TINGWU("通义听悟"),
    VOLCENGINE("豆包 ASR");

    companion object {
        private const val PREFS_NAME = "meeting_prefs"
        private const val PREFS_KEY = "asr_provider_type"

        fun fromPrefs(context: android.content.Context): AsrProviderType {
            val name = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(PREFS_KEY, null) ?: return TINGWU
            return entries.firstOrNull { it.name == name } ?: TINGWU
        }

        fun saveToPrefs(context: android.content.Context, type: AsrProviderType) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putString(PREFS_KEY, type.name).apply()
        }
    }
}

/**
 * 根据用户偏好创建 ASR Provider 实例，同时返回密钥缺失提示（如有）。
 * @return Pair<provider, warningMessage> — warningMessage 为 null 表示密钥齐全
 */
fun createAsrProvider(context: android.content.Context): Pair<AsrProvider, String?> {
    val type = AsrProviderType.fromPrefs(context)
    return when (type) {
        AsrProviderType.TINGWU -> {
            val missing = checkTingwuKeys()
            TingwuAsrProvider() to missing
        }
        AsrProviderType.VOLCENGINE -> {
            val missing = checkVolcengineKeys()
            VolcengineAsrProvider() to missing
        }
    }
}

/** 检查火山引擎 ASR 必要密钥是否配置，返回缺失项描述（null = 齐全） */
fun checkVolcengineKeys(): String? {
    val missing = mutableListOf<String>()
    if (com.example.meetingtranscriber.BuildConfig.VOLCENGINE_ASR_API_KEY.isBlank() &&
        com.example.meetingtranscriber.BuildConfig.VOLCENGINE_ASR_ACCESS_TOKEN.isBlank()
    ) {
        missing.add("API Key 或 Access Token")
    }
    if (com.example.meetingtranscriber.BuildConfig.VOLCENGINE_ASR_RESOURCE_ID.isBlank()) {
        missing.add("Resource ID")
    }
    return if (missing.isEmpty()) null
    else "豆包 ASR 缺少: ${missing.joinToString("、")}"
}

/** 检查通义听悟必要密钥是否配置，返回缺失项描述（null = 齐全） */
fun checkTingwuKeys(): String? {
    val missing = mutableListOf<String>()
    if (com.example.meetingtranscriber.BuildConfig.ALIYUN_ACCESS_KEY_ID.isBlank())
        missing.add("AccessKey ID")
    if (com.example.meetingtranscriber.BuildConfig.ALIYUN_ACCESS_KEY_SECRET.isBlank())
        missing.add("AccessKey Secret")
    if (com.example.meetingtranscriber.BuildConfig.ALIYUN_TINGWU_APP_KEY.isBlank())
        missing.add("App Key")
    return if (missing.isEmpty()) null
    else "通义听悟缺少: ${missing.joinToString("、")}"
}
