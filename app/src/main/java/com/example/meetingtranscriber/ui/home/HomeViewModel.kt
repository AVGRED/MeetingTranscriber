package com.example.meetingtranscriber.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.engine.AsrEngineType
import com.example.meetingtranscriber.engine.LlmEngineType
import com.example.meetingtranscriber.network.NetworkMonitor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)

    // ── 引擎状态 ──

    val asrEngineName: StateFlow<String> = MutableStateFlow(prefs.preferredAsrEngine.displayName)

    // 初值中性 true，由 init 的 IO 协程回填（securePrefs/文件 stat 不进主线程构造函数）
    val asrHasKey: StateFlow<Boolean> = MutableStateFlow(true)

    val llmEngineName: StateFlow<String> = MutableStateFlow(prefs.preferredLlmEngine.displayName)

    val llmHasKey: StateFlow<Boolean> = MutableStateFlow(true)

    // ── 网络状态 ──

    val networkAvailable: StateFlow<Boolean> = MutableStateFlow(NetworkMonitor.isNetworkAvailable)

    // ── 问候语 ──

    val greeting: String
        get() {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return when (hour) {
                in 6..11 -> "早上好"
                in 12..13 -> "中午好"
                in 14..17 -> "下午好"
                else -> "晚上好"
            }
        }

    val todayDate: String
        get() {
            val cal = java.util.Calendar.getInstance()
            return "${cal.get(java.util.Calendar.YEAR)}年${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日"
        }

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            refreshEngineBadges()
        }
        observeNetwork()
    }

    /** 引擎角标回填（须在 IO 线程：触碰 securePrefs + 模型文件 stat） */
    private fun refreshEngineBadges() {
        (asrHasKey as MutableStateFlow).value = when (val asrType = prefs.preferredAsrEngine) {
            AsrEngineType.FUNASR_CLOUD -> prefs.hasFunAsrCloudUrl()
            AsrEngineType.TINGWU_CLOUD -> prefs.hasTingwuKeys()
            AsrEngineType.VOLCENGINE_CLOUD -> prefs.hasVolcengineKeys()
            AsrEngineType.FUNASR_LOCAL -> true
            // 阿里 Paraformer/讯飞/腾讯云/百度 等通用云端 ASR
            else -> com.example.meetingtranscriber.engine.asr.CloudAsrProvider
                .of(asrType)?.hasKeys(prefs) ?: false
        }
        (llmHasKey as MutableStateFlow).value = when (val type = prefs.preferredLlmEngine) {
            LlmEngineType.DOUBAO_CLOUD -> prefs.hasArkKey()
            LlmEngineType.DASHSCOPE_CLOUD -> prefs.hasDashScopeKey()
            // DeepSeek/Kimi/智谱/硅基流动 等 OpenAI 兼容厂家
            else -> prefs.hasLlmKey(type)
        }
    }

    private fun observeNetwork() {
        viewModelScope.launch {
            NetworkMonitor.networkState.collect { available ->
                (networkAvailable as MutableStateFlow).value = available
            }
        }
    }

    /** 刷新状态（onResume 时调用） */
    fun refresh() {
        (asrEngineName as MutableStateFlow).value = prefs.preferredAsrEngine.displayName
        (llmEngineName as MutableStateFlow).value = prefs.preferredLlmEngine.displayName
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            refreshEngineBadges()
        }
    }
}
