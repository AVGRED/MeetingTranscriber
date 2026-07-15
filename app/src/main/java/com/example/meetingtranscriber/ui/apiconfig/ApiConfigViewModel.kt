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
    private val _dashScopeModel = MutableStateFlow("")
    val dashScopeModel: StateFlow<String> = _dashScopeModel

    // ── OpenAI 兼容厂家（DeepSeek/Kimi/智谱/硅基流动）共用卡片字段：
    //    对应"当前选中厂家"的 Key/型号，切换厂家时经 refreshCompatFields 换装 ──
    private val _compatApiKey = MutableStateFlow("")
    val compatApiKey: StateFlow<String> = _compatApiKey
    private val _compatModel = MutableStateFlow("")
    val compatModel: StateFlow<String> = _compatModel

    // ── 通用云端 ASR（阿里 Paraformer/讯飞/腾讯云/百度）共用卡片字段：
    //    3 个凭证槽位，含义随所选厂家变化（见 CloudAsrProvider.fields） ──
    private val _asrCreds = List(3) { MutableStateFlow("") }
    val asrCreds: List<StateFlow<String>> = _asrCreds

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
            _dashScopeModel.value = prefs.getLlmModel(LlmEngineType.DASHSCOPE_CLOUD)
            refreshCompatFieldsBlocking(prefs.preferredLlmEngine)
            refreshAsrFieldsBlocking(prefs.preferredAsrEngine)
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

    fun saveDashScopeKey(apiKey: String, model: String = _dashScopeModel.value) {
        _dashScopeApiKey.value = apiKey
        _dashScopeModel.value = model
        viewModelScope.launch(Dispatchers.IO) {
            prefs.dashScopeApiKey = apiKey
            prefs.setLlmModel(LlmEngineType.DASHSCOPE_CLOUD, model)
        }
    }

    // ── OpenAI 兼容厂家（共用卡片） ──

    /** 切换到 OpenAI 兼容厂家时，把该厂家已存的 Key/型号换装进共用卡片 */
    fun refreshCompatFields(type: LlmEngineType) {
        viewModelScope.launch(Dispatchers.IO) { refreshCompatFieldsBlocking(type) }
    }

    private fun refreshCompatFieldsBlocking(type: LlmEngineType) {
        if (com.example.meetingtranscriber.engine.llm.OpenAiCompatProvider.of(type) == null) return
        _compatApiKey.value = prefs.getLlmApiKey(type)
        _compatModel.value = prefs.getLlmModel(type)
    }

    fun saveCompatConfig(type: LlmEngineType, apiKey: String, model: String) {
        _compatApiKey.value = apiKey
        _compatModel.value = model
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setLlmApiKey(type, apiKey)
            prefs.setLlmModel(type, model)
        }
    }

    fun clearCompatConfig(type: LlmEngineType) {
        saveCompatConfig(type, "", "")
    }

    // ── 通用云端 ASR（共用卡片） ──

    /** 切换到通用云端 ASR 厂家时，把该厂家已存的凭证换装进共用卡片 */
    fun refreshAsrFields(type: AsrEngineType) {
        viewModelScope.launch(Dispatchers.IO) { refreshAsrFieldsBlocking(type) }
    }

    private fun refreshAsrFieldsBlocking(type: AsrEngineType) {
        if (com.example.meetingtranscriber.engine.asr.CloudAsrProvider.of(type) == null) return
        _asrCreds.forEachIndexed { i, flow -> flow.value = prefs.getAsrCred(type, i) }
    }

    fun saveAsrCreds(type: AsrEngineType, creds: List<String>) {
        _asrCreds.forEachIndexed { i, flow -> flow.value = creds.getOrElse(i) { "" } }
        viewModelScope.launch(Dispatchers.IO) {
            repeat(3) { i -> prefs.setAsrCred(type, i, creds.getOrElse(i) { "" }) }
        }
    }

    fun clearAsrCreds(type: AsrEngineType) {
        saveAsrCreds(type, listOf("", "", ""))
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
        saveDashScopeKey("", "")
    }
}
