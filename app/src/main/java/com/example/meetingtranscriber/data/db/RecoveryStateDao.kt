package com.example.meetingtranscriber.data.db

import androidx.room.*

@Dao
interface RecoveryStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: RecoveryStateEntity)

    @Query("SELECT * FROM recovery_state ORDER BY lastSavedAt DESC LIMIT 1")
    suspend fun getLatest(): RecoveryStateEntity?

    @Query("DELETE FROM recovery_state WHERE meetingId = :meetingId")
    suspend fun delete(meetingId: Long): Int

    @Query("DELETE FROM recovery_state")
    suspend fun deleteAll()
}
