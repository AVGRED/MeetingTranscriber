package com.example.meetingtranscriber.ui.home

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.MeetingApplication
import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.data.repository.MeetingRepository
import com.example.meetingtranscriber.engine.AsrEngineType
import com.example.meetingtranscriber.engine.LlmEngineType
import com.example.meetingtranscriber.engine.llm.ModelDownloadManager
import com.example.meetingtranscriber.network.NetworkMonitor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    // DB 懒加载：getInstance 首调可能触发 SQLCipher 打开/明文迁移，不能在主线程构造函数付出
    private val db by lazy { AppDatabase.getInstance(application) }
    private val repo by lazy { MeetingRepository(db) }
    private val modelDownloadManager = ModelDownloadManager(application)

    // ── 引擎状态 ──

    val asrEngineName: StateFlow<String> = MutableStateFlow(prefs.preferredAsrEngine.displayName)

    // 初值中性 true，由 init 的 IO 协程回填（securePrefs/文件 stat 不进主线程构造函数）
    val asrHasKey: StateFlow<Boolean> = MutableStateFlow(true)

    val llmEngineName: StateFlow<String> = MutableStateFlow(prefs.preferredLlmEngine.displayName)

    val llmHasKey: StateFlow<Boolean> = MutableStateFlow(true)

    // ── 网络状态 ──

    val networkAvailable: StateFlow<Boolean> = MutableStateFlow(NetworkMonitor.isNetworkAvailable)

    // ── 模型下载 ──

    val modelDownloadProgress: StateFlow<ModelDownloadManager.DownloadState> =
        modelDownloadManager.downloadProgress

    val modelDownloadProgressPercent: StateFlow<Int> = modelDownloadProgress
        .map { it.percent }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 是否需要下载 Qwen 模型（偏好本地 LLM + 模型未下载 + 未在下载中） */
    val isModelDownloadNeeded: StateFlow<Boolean> = combine(
        modelDownloadProgress,
        MutableStateFlow(prefs.preferredLlmEngine)
    ) { progress, preferred ->
        preferred == LlmEngineType.QWEN_LOCAL &&
        progress.status != ModelDownloadManager.DownloadState.Status.COMPLETED &&
        progress.status != ModelDownloadManager.DownloadState.Status.DOWNLOADING &&
        progress.status != ModelDownloadManager.DownloadState.Status.VERIFYING
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ── 统计 ──

    private val _meetingCount = MutableStateFlow(0)
    val meetingCount: StateFlow<Int> = _meetingCount

    private val _recordingCount = MutableStateFlow(0)
    val recordingCount: StateFlow<Int> = _recordingCount

    /** 卡片小字：无录音时长时仅"条录音"，否则附加现存录音总时长 */
    private val _recordingLabel = MutableStateFlow("条录音")
    val recordingLabel: StateFlow<String> = _recordingLabel

    private val _freeStorageGB = MutableStateFlow("--")
    val freeStorageGB: StateFlow<String> = _freeStorageGB

    // ── 最近会议 ──

    private val _recentMeetings = MutableStateFlow<List<MeetingInfo>>(emptyList())
    val recentMeetings: StateFlow<List<MeetingInfo>> = _recentMeetings

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
        loadHomeData()
        observeNetwork()
    }

    /** 引擎角标回填（须在 IO 线程：触碰 securePrefs + 模型文件 stat） */
    private fun refreshEngineBadges() {
        (asrHasKey as MutableStateFlow).value = when (prefs.preferredAsrEngine) {
            AsrEngineType.FUNASR_CLOUD -> prefs.hasFunAsrCloudUrl()
            AsrEngineType.TINGWU_CLOUD -> prefs.hasTingwuKeys()
            AsrEngineType.VOLCENGINE_CLOUD -> prefs.hasVolcengineKeys()
            AsrEngineType.FUNASR_LOCAL -> true
        }
        (llmHasKey as MutableStateFlow).value = when (prefs.preferredLlmEngine) {
            LlmEngineType.QWEN_LOCAL -> modelDownloadManager.isModelDownloaded()
            LlmEngineType.DOUBAO_CLOUD -> prefs.hasArkKey()
            LlmEngineType.DASHSCOPE_CLOUD -> prefs.hasDashScopeKey()
        }
    }

    /** 统计 + 最近会议合并为一次全表查询（原先 loadStats/loadRecentMeetings 各查一遍） */
    private fun loadHomeData() {
        viewModelScope.launch {
            val meetings = repo.getAllMeetingsOnce()
            _meetingCount.value = meetings.size
            _recentMeetings.value = meetings.filter { !it.isArchived }.take(3)
            // 条录音 = 录音文件仍存在的会议数（30 天清理后不再计入，与实际可播放的录音对齐）
            val withRecording = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                meetings.filter { m ->
                    !m.audioFilePath.isNullOrBlank() && java.io.File(m.audioFilePath).exists()
                }
            }
            _recordingCount.value = withRecording.size
            val totalSeconds = withRecording.sumOf { it.durationSeconds.toLong() }
            _recordingLabel.value = when {
                totalSeconds <= 0 -> "条录音"
                totalSeconds >= 2 * 3600 -> "条录音·" + String.format("%.1f", totalSeconds / 3600.0) + "小时"
                else -> "条录音·${(totalSeconds + 59) / 60}分钟"
            }

            // 存储空间
            try {
                val stat = StatFs(getApplication<Application>().filesDir.absolutePath)
                val freeBytes = stat.availableBytes
                _freeStorageGB.value = String.format("%.1f", freeBytes / (1024.0 * 1024 * 1024))
            } catch (_: Exception) {
                _freeStorageGB.value = "--"
            }
        }
    }

    private fun observeNetwork() {
        viewModelScope.launch {
            NetworkMonitor.networkState.collect { available ->
                (networkAvailable as MutableStateFlow).value = available
            }
        }
    }

    /** 触发 Qwen 模型下载 */
    fun downloadQwenModel() {
        viewModelScope.launch {
            modelDownloadManager.download().onFailure { e ->
                android.util.Log.e("HomeViewModel", "模型下载失败: ${e.message}")
            }
        }
    }

    /** 刷新统计数据（onResume 时调用） */
    fun refresh() {
        loadHomeData()
        (asrEngineName as MutableStateFlow).value = prefs.preferredAsrEngine.displayName
        (llmEngineName as MutableStateFlow).value = prefs.preferredLlmEngine.displayName
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            refreshEngineBadges()
        }
    }
}
