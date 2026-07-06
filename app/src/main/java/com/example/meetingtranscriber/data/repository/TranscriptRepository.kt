package com.example.meetingtranscriber.data.repository

import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.db.TranscriptEntity
import com.example.meetingtranscriber.data.model.TranscriptSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TranscriptRepository(private val db: AppDatabase) {

    private val transcriptDao = db.transcriptDao()

    fun getSegments(meetingId: Long): Flow<List<TranscriptSegment>> {
        return transcriptDao.getSegmentsByMeeting(meetingId).map { list ->
            list.filter { !it.isInterim }.map { it.toModel() }
        }
    }

    suspend fun getSegmentsOnce(meetingId: Long): List<TranscriptSegment> {
        return transcriptDao.getSegmentsByMeetingOnce(meetingId)
            .filter { !it.isInterim }
            .map { it.toModel() }
    }

    suspend fun saveSegment(segment: TranscriptSegment) {
        transcriptDao.insert(segment.toEntity())
    }

    suspend fun getSpeakerCount(meetingId: Long): Int {
        return transcriptDao.getDisplaySpeakers(meetingId).size
    }

    suspend fun search(meetingId: Long, query: String): List<TranscriptSegment> {
        return transcriptDao.search(meetingId, query).map { it.toModel() }
    }

    suspend fun deleteInterimSegments(meetingId: Long) {
        transcriptDao.deleteInterimSegments(meetingId)
    }

    suspend fun renameSpeaker(meetingId: Long, speakerId: String, newName: String): Int {
        return transcriptDao.updateDisplaySpeaker(meetingId, speakerId, newName)
    }

    private fun TranscriptEntity.toModel() = TranscriptSegment(
        id = id,
        meetingId = meetingId,
        speakerId = speakerId,
        displaySpeaker = displaySpeaker,
        text = text,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        sentenceId = sentenceId,
        isInterim = isInterim,
        createdAt = createdAt,
        topicId = topicId
    )

    private fun TranscriptSegment.toEntity() = TranscriptEntity(
        id = id,
        meetingId = meetingId,
        speakerId = speakerId,
        displaySpeaker = displaySpeaker,
        text = text,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        sentenceId = sentenceId,
        isInterim = isInterim,
        createdAt = createdAt,
        topicId = topicId
    )

    suspend fun updateTopicIds(topicMap: Map<Int, List<TranscriptSegment>>) {
        for ((topicId, segments) in topicMap) {
            for (seg in segments) {
                transcriptDao.updateTopicId(seg.id, topicId)
            }
        }
    }

    suspend fun updateText(segmentId: Long, text: String) {
        transcriptDao.updateText(segmentId, text)
    }
}
