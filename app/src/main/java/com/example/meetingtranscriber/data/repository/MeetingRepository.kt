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

    /** 标题搜索下推 SQL（替代全表读 + 内存过滤） */
    fun searchMeetings(query: String): Flow<List<MeetingInfo>> {
        return meetingDao.searchMeetings(query).map { entities ->
            entities.map { it.toInfo() }
        }
    }

    suspend fun getMeeting(id: Long): MeetingInfo? {
        return meetingDao.getById(id)?.toInfo()
    }

    suspend fun createMeeting(title: String, tag: String? = null): Long {
        val entity = MeetingEntity(
            title = title,
            startTime = System.currentTimeMillis(),
            tag = tag
        )
        return meetingDao.insert(entity)
    }

    suspend fun createOfflineMeeting(title: String, startTime: Long, tag: String? = null): Long {
        val entity = MeetingEntity(
            title = title,
            startTime = startTime,
            isOffline = true,
            tag = tag
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

    /** 重算并回写说话人数（声纹迟到判定合并标签后修正用） */
    suspend fun updateSpeakerCount(meetingId: Long) {
        val count = transcriptDao.getDisplaySpeakers(meetingId).size
        meetingDao.updateSpeakerCount(meetingId, count)
    }

    suspend fun deleteMeeting(meetingId: Long) {
        meetingDao.deleteById(meetingId)
    }

    suspend fun updateAudioFilePath(meetingId: Long, path: String) {
        meetingDao.updateAudioFilePath(meetingId, path)
    }

    suspend fun archiveMeeting(meetingId: Long) {
        meetingDao.archiveMeeting(meetingId, System.currentTimeMillis())
    }

    suspend fun restoreMeeting(meetingId: Long) {
        meetingDao.restoreMeeting(meetingId)
    }

    suspend fun permanentDelete(meetingId: Long) {
        meetingDao.permanentDelete(meetingId)
    }

    fun getArchivedMeetings(): Flow<List<MeetingInfo>> {
        return meetingDao.getArchivedMeetings().map { entities ->
            entities.map { it.toInfo() }
        }
    }

    suspend fun getAllMeetingsOnce(): List<MeetingInfo> {
        return meetingDao.getAllMeetingsOnce().map { it.toInfo() }
    }

    fun getMeetingsByTag(tag: String): Flow<List<MeetingInfo>> {
        return meetingDao.getMeetingsByTag(tag).map { entities ->
            entities.map { it.toInfo() }
        }
    }

    private fun MeetingEntity.toInfo() = MeetingInfo(
        id = id,
        title = title,
        startTime = startTime,
        endTime = endTime,
        durationSeconds = durationSeconds,
        speakerCount = speakerCount,
        segmentCount = segmentCount,
        summary = summary,
        isOffline = isOffline,
        audioFilePath = audioFilePath,
        isArchived = isArchived,
        archivedAt = archivedAt,
        tag = tag,
        asrEngineType = asrEngineType,
        llmEngineType = llmEngineType,
        dialectUsed = dialectUsed
    )
}
