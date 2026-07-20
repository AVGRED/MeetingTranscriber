package com.example.meetingtranscriber.ui.history

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.data.model.TranscriptSegment
import com.example.meetingtranscriber.data.repository.MeetingRepository
import com.example.meetingtranscriber.data.repository.TranscriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 统一筛选状态 —— 单次更新避免 [toggleArchived] 双 emit 导致 Room 查询被取消重建。
 */
private data class FilterState(
    val showArchived: Boolean = false,
    val searchQuery: String = ""
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val db = AppDatabase.getInstance(application)
    private val meetingRepository = MeetingRepository(db)
    private val transcriptRepository = TranscriptRepository(db)

    // ── 统计（从 meetings 派生，自动保持最新） ──

    private val _meetingCount = MutableStateFlow(0)
    val meetingCount: StateFlow<Int> = _meetingCount

    private val _recordingCount = MutableStateFlow(0)
    val recordingCount: StateFlow<Int> = _recordingCount

    private val _recordingLabel = MutableStateFlow("条录音")
    val recordingLabel: StateFlow<String> = _recordingLabel

    private val _freeStorageGB = MutableStateFlow("--")
    val freeStorageGB: StateFlow<String> = _freeStorageGB

    // ── 筛选 ──

    private val _filter = MutableStateFlow(FilterState())

    /** 是否展示回收站（从 _filter 派生，供 Fragment observe） */
    val showArchived: StateFlow<Boolean> = _filter
        .map { it.showArchived }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── 会议列表 ──

    /**
     * 单一 flatMapLatest：根据 [FilterState] 选择 Room Flow。
     *
     * 修复要点：
     * - 合并筛选条件为一个 [FilterState]，消除嵌套 combine 竞态。
     * - [toggleArchived] 只触发一次 _filter 更新，不会因双 emit 导致 Room 查询被取消重建。
     * - [SharingStarted.WhileSubscribed(5000)] 在离开历史 Tab 5s 内保持 Room 订阅活跃，
     *   回来时立即拿到缓存列表，无空白闪烁。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val meetings: StateFlow<List<MeetingInfo>> = _filter
        .flatMapLatest { f ->
            val roomFlow = when {
                f.showArchived -> meetingRepository.getArchivedMeetings()
                f.searchQuery.isNotBlank() -> meetingRepository.searchMeetings(f.searchQuery)
                else -> meetingRepository.getAllMeetings()
            }
            // 归档视图下的搜索：SQL 已限定范围，再叠加标题模糊过滤
            if (f.searchQuery.isNotBlank() && f.showArchived) {
                roomFlow.map { list ->
                    list.filter { it.title.contains(f.searchQuery, ignoreCase = true) }
                }
            } else {
                roomFlow
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 首次数据到达标记：防止 Fragment 在初始 emptyList() 阶段闪空白屏 */
    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized

    init {
        // 统计跟随 meetings 保持最新；首次非初始值到达后标记已初始化
        viewModelScope.launch {
            meetings.collect { list ->
                _initialized.value = true
                refreshStats(list)
            }
        }
        // 启动时也做一次完整统计（含磁盘空间）
        viewModelScope.launch { loadStatsFromDb() }
    }

    /** 手动刷新（SwipeRefreshLayout 回调） */
    fun refresh() {
        viewModelScope.launch {
            loadStatsFromDb()
        }
    }

    fun toggleArchived() {
        _filter.update { it.copy(showArchived = !it.showArchived) }
    }

    fun setSearchQuery(query: String) {
        _filter.update { it.copy(searchQuery = query) }
    }

    fun archiveMeeting(id: Long) {
        viewModelScope.launch {
            meetingRepository.archiveMeeting(id)
        }
    }

    fun restoreMeeting(id: Long) {
        viewModelScope.launch {
            meetingRepository.restoreMeeting(id)
        }
    }

    fun permanentDelete(id: Long) {
        viewModelScope.launch {
            meetingRepository.permanentDelete(id)
        }
    }

    /** 加载会议 + 转写片段（用于导出） */
    suspend fun loadForExport(meetingId: Long): Pair<MeetingInfo?, List<TranscriptSegment>> {
        val meeting = meetingRepository.getMeeting(meetingId)
        val segments = transcriptRepository.getSegmentsOnce(meetingId)
        return meeting to segments
    }

    // ── 内部实现 ──

    /**
     * 轻量统计刷新：基于当前 meetings 列表原地更新计数与录音时长。
     * 不执行新的 DB 查询，适合列表每次 emit 时调用。
     */
    private suspend fun refreshStats(list: List<MeetingInfo>) {
        _meetingCount.value = list.size

        val withRecording = withContext(Dispatchers.IO) {
            list.filter { m ->
                !m.audioFilePath.isNullOrBlank() && File(m.audioFilePath).exists()
            }
        }
        _recordingCount.value = withRecording.size

        val totalSeconds = withRecording.sumOf { it.durationSeconds.toLong() }
        _recordingLabel.value = when {
            totalSeconds <= 0 -> "条录音"
            totalSeconds >= 2 * 3600 -> "条录音·" + "%.1f".format(totalSeconds / 3600.0) + "小时"
            else -> "条录音·${(totalSeconds + 59) / 60}分钟"
        }
    }

    /**
     * 完整统计刷新：含磁盘剩余空间。
     * [refresh] 和首次 init 时调用。
     */
    private suspend fun loadStatsFromDb() {
        val meetings = withContext(Dispatchers.IO) {
            meetingRepository.getAllMeetingsOnce()
        }
        _meetingCount.value = meetings.size

        val withRecording = withContext(Dispatchers.IO) {
            meetings.filter { m ->
                !m.audioFilePath.isNullOrBlank() && File(m.audioFilePath).exists()
            }
        }
        _recordingCount.value = withRecording.size

        val totalSeconds = withRecording.sumOf { it.durationSeconds.toLong() }
        _recordingLabel.value = when {
            totalSeconds <= 0 -> "条录音"
            totalSeconds >= 2 * 3600 -> "条录音·" + "%.1f".format(totalSeconds / 3600.0) + "小时"
            else -> "条录音·${(totalSeconds + 59) / 60}分钟"
        }

        try {
            val stat = StatFs(app.filesDir.absolutePath)
            _freeStorageGB.value = "%.1f".format(stat.availableBytes / (1024.0 * 1024 * 1024))
        } catch (_: Exception) {
            _freeStorageGB.value = "--"
        }
    }
}
