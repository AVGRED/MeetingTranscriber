package com.example.meetingtranscriber.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val meetingId: Long,
    val lastSyncedAt: Long = 0,
    val syncStatus: String = "none"  // none, synced, pending
)
