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

    @Query("SELECT * FROM meetings WHERE isArchived = 0 AND title LIKE '%' || :query || '%' ORDER BY startTime DESC")
    fun searchMeetings(query: String): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE isArchived = 0 ORDER BY startTime DESC")
    suspend fun getAllMeetingsOnce(): List<MeetingEntity>

    /** 全部会议（含归档）引用的录音路径——孤儿录音清理用，归档会议的录音同样不可删 */
    @Query("SELECT audioFilePath FROM meetings WHERE audioFilePath IS NOT NULL")
    suspend fun getAllAudioPaths(): List<String>

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

    @Query("UPDATE meetings SET speakerCount = :count WHERE id = :meetingId")
    suspend fun updateSpeakerCount(meetingId: Long, count: Int)

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
