package com.example.meetingtranscriber.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.data.model.TranscriptSegment
import com.example.meetingtranscriber.data.repository.MeetingRepository
import com.example.meetingtranscriber.data.repository.TranscriptRepository
import com.example.meetingtranscriber.ui.meeting.MeetingSummaryGenerator
import com.example.meetingtranscriber.util.TextFormatter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val meetingRepository = MeetingRepository(db)
    private val transcriptRepository = TranscriptRepository(db)

    private val meetingId = MutableStateFlow(0L)
    private val _originalSummary = MutableStateFlow<String?>(null)
    val originalSummary: StateFlow<String?> = _originalSummary.asStateFlow()
    private val _isSummaryEdited = MutableStateFlow(false)
    val isSummaryEdited: StateFlow<Boolean> = _isSummaryEdited.asStateFlow()

    val meeting: StateFlow<MeetingInfo?> = meetingId.flatMapLatest { id ->
        flow { emit(meetingRepository.getMeeting(id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val segments: StateFlow<List<TranscriptSegment>> = meetingId.flatMapLatest { id ->
        transcriptRepository.getSegments(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<TranscriptSegment>?>(null)
    val searchResults: StateFlow<List<TranscriptSegment>?> = _searchResults.asStateFlow()

    fun loadMeeting(id: Long) {
        meetingId.value = id
        _searchQuery.value = ""
        _searchResults.value = null
        viewModelScope.launch {
            val m = meetingRepository.getMeeting(id)
            if (m?.summary != null) {
                _originalSummary.value = m.summary
            }
        }
    }

    fun search(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = null
            return
        }
        viewModelScope.launch {
            val results = transcriptRepository.search(meetingId.value, query)
            _searchResults.value = results
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = null
    }

    fun renameSpeaker(speakerId: String, newName: String) {
        viewModelScope.launch {
            transcriptRepository.renameSpeaker(meetingId.value, speakerId, newName)
            loadMeeting(meetingId.value)
        }
    }

    // --- 文本规整 ---
    private var formatBackup: List<Pair<Long, String>>? = null

    fun hasFormatBackup(): Boolean = formatBackup != null

    fun formatAllSegments() {
        viewModelScope.launch {
            val all = transcriptRepository.getSegmentsOnce(meetingId.value)
            if (all.isEmpty()) return@launch

            formatBackup = all
                .map { it.id to it.text }
                .filter { (_, text) -> TextFormatter.spokenNumberToDigits(TextFormatter.format(text)) != text }

            for (seg in all) {
                val formatted = TextFormatter.spokenNumberToDigits(TextFormatter.format(seg.text))
                if (formatted != seg.text) {
                    transcriptRepository.updateText(seg.id, formatted)
                }
            }
            loadMeeting(meetingId.value)
        }
    }

    fun undoFormat() {
        viewModelScope.launch {
            formatBackup?.let { backup ->
                for ((id, original) in backup) {
                    transcriptRepository.updateText(id, original)
                }
                formatBackup = null
                loadMeeting(meetingId.value)
            }
        }
    }

    // --- 纪要编辑 ---
    fun setSummaryEdited(edited: Boolean) {
        _isSummaryEdited.value = edited
    }

    fun saveSummary(text: String) {
        viewModelScope.launch {
            meetingRepository.saveSummary(meetingId.value, text)
            _isSummaryEdited.value = false
        }
    }

    fun regenerateSummary() {
        val m = meeting.value ?: return
        viewModelScope.launch {
            val allSegments = transcriptRepository.getSegmentsOnce(m.id)
            val fullText = allSegments.joinToString("\n") {
                "${it.displaySpeaker} [${it.formattedTime}]: ${it.text}"
            }
            val summary = MeetingSummaryGenerator.generate(fullText)
            meetingRepository.saveSummary(m.id, summary)
            _originalSummary.value = summary
            _isSummaryEdited.value = false
        }
    }

    fun restoreOriginal() {
        viewModelScope.launch {
            _originalSummary.value?.let { original ->
                meetingRepository.saveSummary(meetingId.value, original)
                _isSummaryEdited.value = false
            }
        }
    }
}
