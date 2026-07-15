package com.example.meetingtranscriber.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.data.model.TranscriptSegment
import com.example.meetingtranscriber.data.repository.MeetingRepository
import com.example.meetingtranscriber.data.repository.TranscriptRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val meetingRepository = MeetingRepository(db)
    private val transcriptRepository = TranscriptRepository(db)

    private val _refreshTrigger = MutableStateFlow(0)
    private val _showArchived = MutableStateFlow(false)
    val showArchived: StateFlow<Boolean> = _showArchived.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val meetings: StateFlow<List<MeetingInfo>> = _refreshTrigger.flatMapLatest {
        combine(_showArchived, _selectedTag, _searchQuery) { archived, tag, query ->
            Triple(archived, tag, query)
        }
    }.flatMapLatest { (archived, tag, query) ->
        val flow = when {
            archived -> meetingRepository.getArchivedMeetings()
            tag != null -> meetingRepository.getMeetingsByTag(tag)
            query.isNotBlank() -> meetingRepository.searchMeetings(query)  // 下推 SQL
            else -> meetingRepository.getAllMeetings()
        }
        // 归档/标签视图下搜索仍走内存过滤（组合少见，保持原行为）
        if (query.isNotBlank() && (archived || tag != null)) {
            flow.map { meetings -> meetings.filter { it.title.contains(query, ignoreCase = true) } }
        } else {
            flow
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refresh() {
        _refreshTrigger.value = _refreshTrigger.value + 1
    }

    fun toggleArchived() {
        _showArchived.value = !_showArchived.value
        _selectedTag.value = null
    }

    fun setTagFilter(tag: String?) {
        _selectedTag.value = tag
        _showArchived.value = false
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
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
}
