package com.example.meetingtranscriber.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(
        entity = MeetingEntity::class,
        parentColumns = ["id"],
        childColumns = ["meetingId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("meetingId"), Index("sentenceId")]
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val meetingId: Long,
    val speakerId: String,
    val displaySpeaker: String,
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val sentenceId: Long = 0,
    val isInterim: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val topicId: Int = 0
)
