package com.example.meetingtranscriber.ui.apiconfig

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.engine.AsrEngineType
import com.example.meetingtranscriber.engine.LlmEngineType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ApiConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)

    // ── ASR 引擎偏好 ──
    private val _preferredAsrEngine = MutableStateFlow(prefs.preferredAsrEngine)
    val preferredAsrEngine: StateFlow<AsrEngineType> = _preferredAsrEngine

    // ── LLM 引擎偏好 ──
    private val _preferredLlmEngine = MutableStateFlow(prefs.preferredLlmEngine)
    val preferredLlmEngine: StateFlow<LlmEngineType> = _preferredLlmEngine

    // ── 自动降级 ──
    private val _autoFallback = MutableStateFlow(prefs.autoFallback)
    val autoFallback: StateFlow<Boolean> = _autoFallback

    // ── FunASR 云端 URL ──
    private val _funasrCloudUrl = MutableStateFlow(prefs.funasrCloudUrl)
    val funasrCloudUrl: StateFlow<String> = _funasrCloudUrl

    // ── 通义听悟密钥 ──
    private val _tingwuAkId = MutableStateFlow(prefs.tingwuAccessKeyId)
    val tingwuAkId: StateFlow<String> = _tingwuAkId
    private val _tingwuAkSecret = MutableStateFlow(prefs.tingwuAccessKeySecret)
    val tingwuAkSecret: StateFlow<String> = _tingwuAkSecret
    private val _tingwuAppKey = MutableStateFlow(prefs.tingwuAppKey)
    val tingwuAppKey: StateFlow<String> = _tingwuAppKey

    // ── 豆包 ASR 密钥 ──
    private val _volcAsrApiKey = MutableStateFlow(prefs.volcengineAsrApiKey)
    val volcAsrApiKey: StateFlow<String> = _volcAsrApiKey
    private val _volcAsrToken = MutableStateFlow(prefs.volcengineAsrAccessToken)
    val volcAsrToken: StateFlow<String> = _volcAsrToken

    // ── 豆包 LLM 密钥 ──
    private val _arkApiKey = MutableStateFlow(prefs.arkApiKey)
    val arkApiKey: StateFlow<String> = _arkApiKey
    private val _arkEndpointId = MutableStateFlow(prefs.arkEndpointId)
    val arkEndpointId: StateFlow<String> = _arkEndpointId

    // ── DashScope 密钥 ──
    private val _dashScopeApiKey = MutableStateFlow(prefs.dashScopeApiKey)
    val dashScopeApiKey: StateFlow<String> = _dashScopeApiKey

    // ═══════════════════════════════════════════════════════════
    // Mutators
    // ═══════════════════════════════════════════════════════════

    fun setPreferredAsrEngine(type: AsrEngineType) {
        prefs.preferredAsrEngine = type
        _preferredAsrEngine.value = type
    }

    fun setPreferredLlmEngine(type: LlmEngineType) {
        prefs.preferredLlmEngine = type
        _preferredLlmEngine.value = type
    }

    fun setAutoFallback(enabled: Boolean) {
        prefs.autoFallback = enabled
        _autoFallback.value = enabled
    }

    fun setFunasrCloudUrl(url: String) {
        prefs.funasrCloudUrl = url
        _funasrCloudUrl.value = url
    }

    fun saveTingwuKeys(akId: String, akSecret: String, appKey: String) {
        prefs.tingwuAccessKeyId = akId
        prefs.tingwuAccessKeySecret = akSecret
        prefs.tingwuAppKey = appKey
        _tingwuAkId.value = akId
        _tingwuAkSecret.value = akSecret
        _tingwuAppKey.value = appKey
    }

    fun saveVolcengineKeys(apiKey: String, accessToken: String) {
        prefs.volcengineAsrApiKey = apiKey
        prefs.volcengineAsrAccessToken = accessToken
        _volcAsrApiKey.value = apiKey
        _volcAsrToken.value = accessToken
    }

    fun saveArkKey(apiKey: String, endpointId: String) {
        prefs.arkApiKey = apiKey
        prefs.arkEndpointId = endpointId
        _arkApiKey.value = apiKey
        _arkEndpointId.value = endpointId
    }

    fun saveDashScopeKey(apiKey: String) {
        prefs.dashScopeApiKey = apiKey
        _dashScopeApiKey.value = apiKey
    }

    fun clearTingwuKeys() {
        saveTingwuKeys("", "", "")
    }

    fun clearVolcengineKeys() {
        saveVolcengineKeys("", "")
    }

    fun clearArkKey() {
        saveArkKey("", "")
    }

    fun clearDashScopeKey() {
        saveDashScopeKey("")
    }
}
