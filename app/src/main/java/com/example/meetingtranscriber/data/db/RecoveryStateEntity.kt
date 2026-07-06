package com.example.meetingtranscriber.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recovery_state")
data class RecoveryStateEntity(
    @PrimaryKey
    val meetingId: Long,
    val title: String,
    val startTime: Long,
    val isOfflineMode: Boolean = false,
    val isDemoMode: Boolean = false,
    val currentTaskId: String = "",
    val audioBufferFilePath: String? = null,
    val audioBufferFrameCount: Int = 0,
    val speakerLabelMapJson: String = "{}",
    val pendingSegmentsJson: String = "[]",
    val lastSavedAt: Long = System.currentTimeMillis()
)
