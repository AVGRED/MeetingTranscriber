package com.example.mt.data.repository

import com.example.mt.data.model.TranscriptSegment
import com.example.mt.db.TranscriptSegments
import com.example.mt.db.TranscriptSegmentsQueries
import io.github.aakira.napier.Napier

/**
 * 转写段落仓库 — 封装 transcript_segments 表的 CRUD。
 */
class TranscriptRepository(
    private val transcriptQueries: TranscriptSegmentsQueries,
) {

    fun insert(segment: TranscriptSegment): Long {
        transcriptQueries.insertSegment(
            meetingId = segment.meetingId,
            speakerId = segment.speakerId,
            displaySpeaker = segment.displaySpeaker,
            text = segment.text,
            startTimeMs = segment.startTimeMs,
            endTimeMs = segment.endTimeMs,
            sentenceId = segment.sentenceId,
            isInterim = if (segment.isInterim) 1L else 0L,
            createdAt = segment.createdAt,
            topicId = segment.topicId.toLong(),
        )
        return transcriptQueries.selectLastInsertId().executeAsOne()
    }

    fun getByMeeting(meetingId: Long): List<TranscriptSegment> =
        transcriptQueries.selectSegmentsByMeeting(meetingId)
            .executeAsList()
            .map { it.toTranscriptSegment() }

    fun getBySentence(meetingId: Long, sentenceId: Long): TranscriptSegment? =
        transcriptQueries.selectSegmentBySentence(meetingId, sentenceId)
            .executeAsOneOrNull()
            ?.toTranscriptSegment()

    fun getMaxSentenceId(meetingId: Long): Long =
        transcriptQueries.selectMaxSentenceId(meetingId).executeAsOneOrNull() ?: 0L

    fun deleteInterim(meetingId: Long) {
        transcriptQueries.deleteInterimSegments(meetingId)
    }

    fun deleteByMeeting(meetingId: Long) {
        transcriptQueries.deleteSegmentsByMeeting(meetingId)
    }

    fun countByMeeting(meetingId: Long): Long =
        transcriptQueries.countSegmentsByMeeting(meetingId).executeAsOne()

    fun countSpeakers(meetingId: Long): Long =
        transcriptQueries.countDistinctSpeakers(meetingId).executeAsOne()

    fun updateSpeakerDisplay(meetingId: Long, speakerId: String, displayName: String) {
        transcriptQueries.updateSpeakerDisplay(displayName, meetingId, speakerId)
    }

    fun finalizeInterim(meetingId: Long) {
        transcriptQueries.finalizeInterimSegments(meetingId)
    }

    companion object {
        private const val TAG = "TranscriptRepository"
    }
}

fun TranscriptSegments.toTranscriptSegment() = TranscriptSegment(
    id = id,
    meetingId = meetingId,
    speakerId = speakerId,
    displaySpeaker = displaySpeaker,
    text = text,
    startTimeMs = startTimeMs,
    endTimeMs = endTimeMs,
    sentenceId = sentenceId,
    isInterim = isInterim != 0L,
    createdAt = createdAt,
    topicId = topicId.toInt(),
)
