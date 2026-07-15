package com.example.meetingtranscriber

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.meetingtranscriber.engine.LlmEngineType
import com.example.meetingtranscriber.engine.AsrEngineType
import com.example.meetingtranscriber.engine.SummaryStyle

/**
 * 用户偏好与 API Key 存储管理器。
 *
 * API Key 使用 EncryptedSharedPrefs 加密存储；
 * 非敏感偏好使用明文 SharedPreferences。
 * 如果设备不支持加密（Android 5.x），自动回退到明文 SharedPrefs。
 */
class PreferencesManager(context: Context) {

    private val appContext = context.applicationContext

    // ─── 加密存储（API Key，进程级单例：EncryptedSharedPrefs 创建含 Keystore 操作，
    //     成本数百 ms，PreferencesManager 多处实例化，不能每实例重复付） ───
    private val securePrefs: SharedPreferences
        get() = obtainSecurePrefs(appContext)

    /** 后台预热加密存储，避免主线程首触付出 Keystore 成本 */
    fun warmUp() {
        obtainSecurePrefs(appContext)
    }

    // ─── 明文偏好 ───
    private val plainPrefs: SharedPreferences =
        appContext.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════
    // ASR API Key (加密存储)
    // ═══════════════════════════════════════════════════════

    /** 通义听悟 AccessKey ID */
    var tingwuAccessKeyId: String
        get() = securePrefs.getString(KEY_TINGWU_AK_ID, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_TINGWU_AK_ID, value).apply()

    /** 通义听悟 AccessKey Secret */
    var tingwuAccessKeySecret: String
        get() = securePrefs.getString(KEY_TINGWU_AK_SECRET, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_TINGWU_AK_SECRET, value).apply()

    /** 通义听悟 App Key */
    var tingwuAppKey: String
        get() = securePrefs.getString(KEY_TINGWU_APP_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_TINGWU_APP_KEY, value).apply()

    /** 火山方舟 API Key（LLM） */
    var arkApiKey: String
        get() = securePrefs.getString(KEY_ARK_API_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_ARK_API_KEY, value).apply()

    /** 火山方舟推理端点 ID（可选） */
    var arkEndpointId: String
        get() = securePrefs.getString(KEY_ARK_ENDPOINT_ID, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_ARK_ENDPOINT_ID, value).apply()

    /** DashScope API Key（通义千问） */
    var dashScopeApiKey: String
        get() = securePrefs.getString(KEY_DASHSCOPE_API_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_DASHSCOPE_API_KEY, value).apply()

    /** 豆包 ASR API Key */
    var volcengineAsrApiKey: String
        get() = securePrefs.getString(KEY_VOLC_ASR_API_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_VOLC_ASR_API_KEY, value).apply()

    /** 豆包 ASR Access Token */
    var volcengineAsrAccessToken: String
        get() = securePrefs.getString(KEY_VOLC_ASR_TOKEN, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_VOLC_ASR_TOKEN, value).apply()

    // ═══════════════════════════════════════════════════════
    // 通用云端 LLM 密钥/型号（按引擎类型区分；DeepSeek/Kimi/智谱/
    // 硅基流动等 OpenAI 兼容厂家 + DashScope 型号选择共用）
    // ═══════════════════════════════════════════════════════

    /** 云端 LLM API Key（加密存储） */
    fun getLlmApiKey(type: LlmEngineType): String =
        securePrefs.getString("$KEY_LLM_API_KEY_PREFIX${type.name}", "") ?: ""

    fun setLlmApiKey(type: LlmEngineType, value: String) =
        securePrefs.edit().putString("$KEY_LLM_API_KEY_PREFIX${type.name}", value).apply()

    /** 云端 LLM 型号 ID（非敏感，明文存储；空串 = 用引擎默认型号） */
    fun getLlmModel(type: LlmEngineType): String =
        plainPrefs.getString("$KEY_LLM_MODEL_PREFIX${type.name}", "") ?: ""

    fun setLlmModel(type: LlmEngineType, value: String) =
        plainPrefs.edit().putString("$KEY_LLM_MODEL_PREFIX${type.name}", value).apply()

    /** 指定云端 LLM 的 Key 是否已配置 */
    fun hasLlmKey(type: LlmEngineType): Boolean = getLlmApiKey(type).isNotBlank()

    // ═══════════════════════════════════════════════════════
    // 引擎偏好 (明文存储)
    // ═══════════════════════════════════════════════════════

    /** 首选 ASR 引擎 */
    var preferredAsrEngine: AsrEngineType
        get() {
            val name = plainPrefs.getString(KEY_ASR_ENGINE, AsrEngineType.FUNASR_LOCAL.name)
                ?: AsrEngineType.FUNASR_LOCAL.name
            return try { AsrEngineType.valueOf(name) } catch (_: Exception) { AsrEngineType.FUNASR_LOCAL }
        }
        set(value) = plainPrefs.edit().putString(KEY_ASR_ENGINE, value.name).apply()

    /** 首选 LLM 引擎 */
    var preferredLlmEngine: LlmEngineType
        get() {
            val name = plainPrefs.getString(KEY_LLM_ENGINE, LlmEngineType.QWEN_LOCAL.name)
                ?: LlmEngineType.QWEN_LOCAL.name
            return try { LlmEngineType.valueOf(name) } catch (_: Exception) { LlmEngineType.QWEN_LOCAL }
        }
        set(value) = plainPrefs.edit().putString(KEY_LLM_ENGINE, value.name).apply()

    /** 无网络时自动切换到本地引擎 */
    var autoFallback: Boolean
        get() = plainPrefs.getBoolean(KEY_AUTO_FALLBACK, true)
        set(value) = plainPrefs.edit().putBoolean(KEY_AUTO_FALLBACK, value).apply()

    /** 纪要风格 */
    var summaryStyle: SummaryStyle
        get() {
            val name = plainPrefs.getString(KEY_SUMMARY_STYLE, SummaryStyle.STANDARD.name)
                ?: SummaryStyle.STANDARD.name
            return try { SummaryStyle.valueOf(name) } catch (_: Exception) { SummaryStyle.STANDARD }
        }
        set(value) = plainPrefs.edit().putString(KEY_SUMMARY_STYLE, value.name).apply()

    /** 后台静默运行 */
    var backgroundSilent: Boolean
        get() = plainPrefs.getBoolean(KEY_BACKGROUND_SILENT, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_BACKGROUND_SILENT, value).apply()

    /** FunASR 云端服务器地址 (ws://host:port/) */
    var funasrCloudUrl: String
        get() = plainPrefs.getString(KEY_FUNASR_CLOUD_URL, "") ?: ""
        set(value) = plainPrefs.edit().putString(KEY_FUNASR_CLOUD_URL, value).apply()

    // ═══════════════════════════════════════════════════════
    // 批量查询
    // ═══════════════════════════════════════════════════════

    /** 通义听悟 3 个密钥是否全部配置 */
    fun hasTingwuKeys(): Boolean =
        tingwuAccessKeyId.isNotBlank() && tingwuAccessKeySecret.isNotBlank() && tingwuAppKey.isNotBlank()

    /** 豆包 ASR 密钥是否已配置 */
    fun hasVolcengineKeys(): Boolean =
        volcengineAsrApiKey.isNotBlank() || volcengineAsrAccessToken.isNotBlank()

    /** 火山方舟 API Key 是否已配置 */
    fun hasArkKey(): Boolean = arkApiKey.isNotBlank()

    /** DashScope API Key 是否已配置 */
    fun hasDashScopeKey(): Boolean = dashScopeApiKey.isNotBlank()

    /** 是否有任何云端 ASR Key */
    fun hasAnyCloudAsrKey(): Boolean = hasFunAsrCloudUrl() || hasTingwuKeys() || hasVolcengineKeys()

    /** 是否有任何云端 LLM Key */
    fun hasAnyCloudLlmKey(): Boolean = hasArkKey() || hasDashScopeKey() ||
        LlmEngineType.entries.any { it.isCloud && hasLlmKey(it) }

    /** FunASR 云端地址是否已配置 */
    fun hasFunAsrCloudUrl(): Boolean = funasrCloudUrl.isNotBlank()

    companion object {
        private const val TAG = "PreferencesManager"

        @Volatile private var sharedSecurePrefs: SharedPreferences? = null

        private fun obtainSecurePrefs(appContext: Context): SharedPreferences {
            sharedSecurePrefs?.let { return it }
            synchronized(this) {
                sharedSecurePrefs?.let { return it }
                val prefs = try {
                    val masterKey = MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        appContext,
                        SECURE_PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "EncryptedSharedPrefs 不可用，回退到明文: ${e.message}")
                    appContext.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
                }
                sharedSecurePrefs = prefs
                return prefs
            }
        }

        private const val SECURE_PREFS_NAME = "meeting_secure_keys"
        const val PLAIN_PREFS_NAME = "meeting_preferences"

        // 加密 Key
        private const val KEY_TINGWU_AK_ID = "tingwu_access_key_id"
        private const val KEY_TINGWU_AK_SECRET = "tingwu_access_key_secret"
        private const val KEY_TINGWU_APP_KEY = "tingwu_app_key"
        private const val KEY_ARK_API_KEY = "ark_api_key"
        private const val KEY_ARK_ENDPOINT_ID = "ark_endpoint_id"
        private const val KEY_DASHSCOPE_API_KEY = "dashscope_api_key"
        private const val KEY_VOLC_ASR_API_KEY = "volcengine_asr_api_key"
        private const val KEY_VOLC_ASR_TOKEN = "volcengine_asr_access_token"
        private const val KEY_LLM_API_KEY_PREFIX = "llm_api_key_"

        // 明文 Key
        private const val KEY_ASR_ENGINE = "preferred_asr_engine"
        private const val KEY_LLM_ENGINE = "preferred_llm_engine"
        private const val KEY_AUTO_FALLBACK = "auto_fallback"
        private const val KEY_SUMMARY_STYLE = "summary_style"
        private const val KEY_BACKGROUND_SILENT = "background_silent"
        private const val KEY_FUNASR_CLOUD_URL = "funasr_cloud_url"
        private const val KEY_LLM_MODEL_PREFIX = "llm_model_"
    }
}
