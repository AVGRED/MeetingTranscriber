package com.example.meetingtranscriber.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {

    @Insert
    suspend fun insert(meeting: MeetingEntity): Long

    @Update
    suspend fun update(meeting: MeetingEntity)

    @Delete
    suspend fun delete(meeting: MeetingEntity): Int

    @Query("SELECT * FROM meetings WHERE isArchived = 0 ORDER BY startTime DESC")
    fun getAllMeetings(): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE id = :meetingId")
    suspend fun getById(meetingId: Long): MeetingEntity?

    @Query("SELECT * FROM meetings WHERE endTime IS NULL LIMIT 1")
    suspend fun getOngoingMeeting(): MeetingEntity?

    @Query("UPDATE meetings SET endTime = :endTime, durationSeconds = :duration, speakerCount = :speakerCount, segmentCount = :segmentCount WHERE id = :meetingId")
    suspend fun endMeeting(
        meetingId: Long,
        endTime: Long,
        duration: Int,
        speakerCount: Int,
        segmentCount: Int
    )

    @Query("UPDATE meetings SET summary = :summary WHERE id = :meetingId")
    suspend fun updateSummary(meetingId: Long, summary: String)

    @Query("DELETE FROM meetings WHERE id = :meetingId")
    suspend fun deleteById(meetingId: Long): Int

    @Query("SELECT * FROM meetings WHERE isOffline = 1 AND endTime IS NOT NULL ORDER BY startTime DESC")
    suspend fun getOfflineMeetings(): List<MeetingEntity>

    @Query("UPDATE meetings SET audioFilePath = :path WHERE id = :meetingId")
    suspend fun updateAudioFilePath(meetingId: Long, path: String)

    @Query("UPDATE meetings SET isArchived = 1, archivedAt = :archivedAt WHERE id = :meetingId")
    suspend fun archiveMeeting(meetingId: Long, archivedAt: Long)

    @Query("UPDATE meetings SET isArchived = 0, archivedAt = NULL WHERE id = :meetingId")
    suspend fun restoreMeeting(meetingId: Long)

    @Query("DELETE FROM meetings WHERE id = :meetingId AND isArchived = 1")
    suspend fun permanentDelete(meetingId: Long)

    @Query("SELECT * FROM meetings WHERE isArchived = 1 ORDER BY archivedAt DESC")
    fun getArchivedMeetings(): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE isArchived = 0 AND tag = :tag ORDER BY startTime DESC")
    fun getMeetingsByTag(tag: String): Flow<List<MeetingEntity>>
}
