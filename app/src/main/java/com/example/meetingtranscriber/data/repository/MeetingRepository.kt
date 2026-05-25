package com.example.meetingtranscriber.data.repository

import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.db.MeetingEntity
import com.example.meetingtranscriber.data.model.MeetingInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MeetingRepository(private val db: AppDatabase) {

    private val meetingDao = db.meetingDao()
    private val transcriptDao = db.transcriptDao()

    fun getAllMeetings(): Flow<List<MeetingInfo>> {
        return meetingDao.getAllMeetings().map { entities ->
            entities.map { it.toInfo() }
        }
    }

    suspend fun getMeeting(id: Long): MeetingInfo? {
        return meetingDao.getById(id)?.toInfo()
    }

    suspend fun createMeeting(title: String): Long {
        val entity = MeetingEntity(
            title = title,
            startTime = System.currentTimeMillis()
        )
        return meetingDao.insert(entity)
    }

    suspend fun endMeeting(meetingId: Long): MeetingInfo? {
        val meeting = meetingDao.getById(meetingId) ?: return null
        val endTime = System.currentTimeMillis()
        val duration = ((endTime - meeting.startTime) / 1000).toInt()
        val speakerCount = transcriptDao.getDisplaySpeakers(meetingId).size
        val segmentCount = transcriptDao.getSegmentCount(meetingId)

        meetingDao.endMeeting(meetingId, endTime, duration, speakerCount, segmentCount)
        return meetingDao.getById(meetingId)?.toInfo()
    }

    suspend fun saveSummary(meetingId: Long, summary: String) {
        meetingDao.updateSummary(meetingId, summary)
    }

    suspend fun deleteMeeting(meetingId: Long) {
        meetingDao.deleteById(meetingId)
    }

    private fun MeetingEntity.toInfo() = MeetingInfo(
        id = id,
        title = title,
        startTime = startTime,
        endTime = endTime,
        durationSeconds = durationSeconds,
        speakerCount = speakerCount,
        segmentCount = segmentCount,
        summary = summary
    )
}
