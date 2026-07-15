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

    // ─── 加密存储（API Key） ───
    private val securePrefs: SharedPreferences by lazy {
        try {
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
    fun hasAnyCloudLlmKey(): Boolean = hasArkKey() || hasDashScopeKey()

    /** FunASR 云端地址是否已配置 */
    fun hasFunAsrCloudUrl(): Boolean = funasrCloudUrl.isNotBlank()

    companion object {
        private const val TAG = "PreferencesManager"

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

        // 明文 Key
        private const val KEY_ASR_ENGINE = "preferred_asr_engine"
        private const val KEY_LLM_ENGINE = "preferred_llm_engine"
        private const val KEY_AUTO_FALLBACK = "auto_fallback"
        private const val KEY_SUMMARY_STYLE = "summary_style"
        private const val KEY_BACKGROUND_SILENT = "background_silent"
        private const val KEY_FUNASR_CLOUD_URL = "funasr_cloud_url"
    }
}
