package com.example.meetingtranscriber.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.db.VocabularyEntity
import com.example.meetingtranscriber.data.db.VocabularyWordEntity
import com.example.meetingtranscriber.data.db.VocabularyMeetingCrossRef
import com.example.meetingtranscriber.network.VocabularyApiClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val vocabDao = db.vocabularyDao()
    private val apiClient = VocabularyApiClient()

    val vocabularies: StateFlow<List<VocabularyEntity>> = vocabDao.getAllVocabularies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun createVocabulary(name: String, words: List<String>) {
        viewModelScope.launch {
            val entity = VocabularyEntity(name = name, sourceType = "manual")
            val id = vocabDao.insertVocabulary(entity)
            if (words.isNotEmpty()) {
                val wordEntities = words.map { VocabularyWordEntity(vocabularyId = id, word = it.trim()) }
                vocabDao.insertWords(wordEntities)
                val count = vocabDao.getWordCount(id)
                vocabDao.updateVocabularyCloudInfo(id, "", count)
            }
            // 异步上传到云端
            uploadVocabulary(id, name, words)
        }
    }

    fun importFromFile(name: String, words: List<String>) {
        viewModelScope.launch {
            val entity = VocabularyEntity(name = name, wordCount = words.size, sourceType = "import")
            val id = vocabDao.insertVocabulary(entity)
            val wordEntities = words.map { VocabularyWordEntity(vocabularyId = id, word = it.trim()) }
            vocabDao.insertWords(wordEntities)
            val count = vocabDao.getWordCount(id)
            vocabDao.updateVocabularyCloudInfo(id, "", count)
            uploadVocabulary(id, name, words)
        }
    }

    fun deleteVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            if (!vocabulary.vocabularyId.isNullOrBlank()) {
                apiClient.deleteVocabulary(vocabulary.vocabularyId)
            }
            vocabDao.deleteVocabulary(vocabulary.id)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private suspend fun uploadVocabulary(id: Long, name: String, words: List<String>) {
        try {
            val cloudId = apiClient.createVocabulary(name, words)
            if (cloudId != null) {
                vocabDao.updateVocabularyCloudInfo(id, cloudId, words.size)
            } else {
                _errorMessage.value = "词库「$name」上传云端失败"
            }
        } catch (e: Exception) {
            _errorMessage.value = "词库上传异常: ${e.message}"
        }
    }
}
