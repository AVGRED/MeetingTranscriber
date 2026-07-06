package com.example.meetingtranscriber.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE meetingId = :meetingId")
    suspend fun get(meetingId: Long): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE syncStatus = 'pending'")
    suspend fun getPendingSyncs(): List<SyncStateEntity>

    @Query("SELECT * FROM sync_state WHERE syncStatus = 'synced'")
    fun getSyncedMeetings(): Flow<List<SyncStateEntity>>

    @Query("DELETE FROM sync_state WHERE meetingId = :meetingId")
    suspend fun delete(meetingId: Long)
}
