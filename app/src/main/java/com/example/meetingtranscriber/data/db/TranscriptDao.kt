package com.example.meetingtranscriber.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {

    @Insert
    suspend fun insert(segment: TranscriptEntity): Long

    @Insert
    suspend fun insertAll(segments: List<TranscriptEntity>)

    @Query("SELECT * FROM transcript_segments WHERE meetingId = :meetingId ORDER BY startTimeMs ASC")
    fun getSegmentsByMeeting(meetingId: Long): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcript_segments WHERE meetingId = :meetingId ORDER BY startTimeMs ASC")
    suspend fun getSegmentsByMeetingOnce(meetingId: Long): List<TranscriptEntity>

    @Query("SELECT DISTINCT speakerId FROM transcript_segments WHERE meetingId = :meetingId")
    suspend fun getSpeakerIds(meetingId: Long): List<String>

    @Query("SELECT DISTINCT displaySpeaker FROM transcript_segments WHERE meetingId = :meetingId")
    suspend fun getDisplaySpeakers(meetingId: Long): List<String>

    @Query("SELECT COUNT(*) FROM transcript_segments WHERE meetingId = :meetingId AND isInterim = 0")
    suspend fun getSegmentCount(meetingId: Long): Int

    @Query("SELECT * FROM transcript_segments WHERE meetingId = :meetingId AND text LIKE '%' || :query || '%' ORDER BY startTimeMs ASC")
    suspend fun search(meetingId: Long, query: String): List<TranscriptEntity>

    @Query("DELETE FROM transcript_segments WHERE meetingId = :meetingId AND isInterim = 1")
    suspend fun deleteInterimSegments(meetingId: Long): Int

    @Query("DELETE FROM transcript_segments WHERE meetingId = :meetingId")
    suspend fun deleteByMeetingId(meetingId: Long): Int
}
