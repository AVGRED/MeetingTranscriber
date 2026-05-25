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

    @Query("SELECT * FROM meetings ORDER BY startTime DESC")
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
}
