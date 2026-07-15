package com.example.meetingtranscriber.ui.apiconfig

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.engine.AsrEngineType
import com.example.meetingtranscriber.engine.LlmEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    // ── 密钥（加密存储，读取含 Keystore/AES 解密：初值空串，IO 协程回填，
    //     不能在主线程构造函数里同步解密 8 个值） ──
    private val _tingwuAkId = MutableStateFlow("")
    val tingwuAkId: StateFlow<String> = _tingwuAkId
    private val _tingwuAkSecret = MutableStateFlow("")
    val tingwuAkSecret: StateFlow<String> = _tingwuAkSecret
    private val _tingwuAppKey = MutableStateFlow("")
    val tingwuAppKey: StateFlow<String> = _tingwuAppKey

    private val _volcAsrApiKey = MutableStateFlow("")
    val volcAsrApiKey: StateFlow<String> = _volcAsrApiKey
    private val _volcAsrToken = MutableStateFlow("")
    val volcAsrToken: StateFlow<String> = _volcAsrToken

    private val _arkApiKey = MutableStateFlow("")
    val arkApiKey: StateFlow<String> = _arkApiKey
    private val _arkEndpointId = MutableStateFlow("")
    val arkEndpointId: StateFlow<String> = _arkEndpointId

    private val _dashScopeApiKey = MutableStateFlow("")
    val dashScopeApiKey: StateFlow<String> = _dashScopeApiKey

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _tingwuAkId.value = prefs.tingwuAccessKeyId
            _tingwuAkSecret.value = prefs.tingwuAccessKeySecret
            _tingwuAppKey.value = prefs.tingwuAppKey
            _volcAsrApiKey.value = prefs.volcengineAsrApiKey
            _volcAsrToken.value = prefs.volcengineAsrAccessToken
            _arkApiKey.value = prefs.arkApiKey
            _arkEndpointId.value = prefs.arkEndpointId
            _dashScopeApiKey.value = prefs.dashScopeApiKey
        }
    }

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
        _tingwuAkId.value = akId
        _tingwuAkSecret.value = akSecret
        _tingwuAppKey.value = appKey
        viewModelScope.launch(Dispatchers.IO) {
            prefs.tingwuAccessKeyId = akId
            prefs.tingwuAccessKeySecret = akSecret
            prefs.tingwuAppKey = appKey
        }
    }

    fun saveVolcengineKeys(apiKey: String, accessToken: String) {
        _volcAsrApiKey.value = apiKey
        _volcAsrToken.value = accessToken
        viewModelScope.launch(Dispatchers.IO) {
            prefs.volcengineAsrApiKey = apiKey
            prefs.volcengineAsrAccessToken = accessToken
        }
    }

    fun saveArkKey(apiKey: String, endpointId: String) {
        _arkApiKey.value = apiKey
        _arkEndpointId.value = endpointId
        viewModelScope.launch(Dispatchers.IO) {
            prefs.arkApiKey = apiKey
            prefs.arkEndpointId = endpointId
        }
    }

    fun saveDashScopeKey(apiKey: String) {
        _dashScopeApiKey.value = apiKey
        viewModelScope.launch(Dispatchers.IO) {
            prefs.dashScopeApiKey = apiKey
        }
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
