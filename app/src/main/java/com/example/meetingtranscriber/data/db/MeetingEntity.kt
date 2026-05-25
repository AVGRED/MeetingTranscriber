package com.example.meetingtranscriber.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meetings")
data class MeetingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val startTime: Long,
    val endTime: Long? = null,
    val durationSeconds: Int = 0,
    val speakerCount: Int = 0,
    val segmentCount: Int = 0,
    val summary: String? = null
)
