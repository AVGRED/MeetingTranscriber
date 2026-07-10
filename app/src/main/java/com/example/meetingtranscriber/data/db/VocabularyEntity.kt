package com.example.meetingtranscriber.data.db

import androidx.room.*

@Entity(tableName = "vocabulary")
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val vocabularyId: String? = null,
    val wordCount: Int = 0,
    val sourceType: String = "manual",
    val createdTime: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "vocabulary_words",
    foreignKeys = [ForeignKey(
        entity = VocabularyEntity::class,
        parentColumns = ["id"],
        childColumns = ["vocabularyId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("vocabularyId")]
)
data class VocabularyWordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val vocabularyId: Long,
    val word: String,
    val weight: Float = 1.0f
)

@Entity(
    tableName = "vocabulary_meeting_cross_ref",
    primaryKeys = ["vocabularyId", "meetingId"],
    foreignKeys = [
        ForeignKey(entity = VocabularyEntity::class, parentColumns = ["id"], childColumns = ["vocabularyId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("meetingId")]
)
data class VocabularyMeetingCrossRef(
    val vocabularyId: Long,
    val meetingId: Long
)
