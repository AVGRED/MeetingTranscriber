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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val meetingRepository = MeetingRepository(db)
    private val transcriptRepository = TranscriptRepository(db)

    private val meetingId = MutableStateFlow(0L)

    val meeting: StateFlow<MeetingInfo?> = meetingId.flatMapLatest { id ->
        flow { emit(meetingRepository.getMeeting(id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val segments: StateFlow<List<TranscriptSegment>> = meetingId.flatMapLatest { id ->
        transcriptRepository.getSegments(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadMeeting(id: Long) {
        meetingId.value = id
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
        }
    }
}
