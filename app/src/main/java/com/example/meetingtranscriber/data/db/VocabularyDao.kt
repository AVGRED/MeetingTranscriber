package com.example.meetingtranscriber.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {

    // --- 词库 ---
    @Insert
    suspend fun insertVocabulary(vocabulary: VocabularyEntity): Long

    @Update
    suspend fun updateVocabulary(vocabulary: VocabularyEntity)

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun deleteVocabulary(id: Long)

    @Query("SELECT * FROM vocabulary ORDER BY createdTime DESC")
    fun getAllVocabularies(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE id = :id")
    suspend fun getVocabularyById(id: Long): VocabularyEntity?

    @Query("UPDATE vocabulary SET vocabularyId = :cloudId, wordCount = :count WHERE id = :id")
    suspend fun updateVocabularyCloudInfo(id: Long, cloudId: String, count: Int)

    // --- 词汇 ---
    @Insert
    suspend fun insertWords(words: List<VocabularyWordEntity>)

    @Query("DELETE FROM vocabulary_words WHERE vocabularyId = :vocabularyId")
    suspend fun deleteWordsByVocabulary(vocabularyId: Long)

    @Query("SELECT * FROM vocabulary_words WHERE vocabularyId = :vocabularyId")
    suspend fun getWords(vocabularyId: Long): List<VocabularyWordEntity>

    @Query("SELECT COUNT(*) FROM vocabulary_words WHERE vocabularyId = :vocabularyId")
    suspend fun getWordCount(vocabularyId: Long): Int

    // --- 会议关联 ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun linkVocabularyToMeeting(crossRef: VocabularyMeetingCrossRef)

    @Query("DELETE FROM vocabulary_meeting_cross_ref WHERE meetingId = :meetingId")
    suspend fun unlinkMeeting(meetingId: Long)

    @Query("SELECT v.* FROM vocabulary v INNER JOIN vocabulary_meeting_cross_ref r ON v.id = r.vocabularyId WHERE r.meetingId = :meetingId")
    suspend fun getVocabularyForMeeting(meetingId: Long): VocabularyEntity?
}
