package com.example.meetingtranscriber.ui.meeting

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.audio.AudioCaptureManager
import com.example.meetingtranscriber.audio.VADDetector
import com.example.meetingtranscriber.audio.WavPlayer
import com.example.meetingtranscriber.audio.WavRecorder
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.db.RecoveryStateEntity
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.data.model.TranscriptSegment
import com.example.meetingtranscriber.data.repository.MeetingRepository
import com.example.meetingtranscriber.data.repository.TranscriptRepository
import com.example.meetingtranscriber.network.ConnectionState
import com.example.meetingtranscriber.security.CryptoManager
import com.example.meetingtranscriber.MeetingApplication
import com.example.meetingtranscriber.domain.SummaryUseCase
import com.example.meetingtranscriber.domain.TranscriptionUseCase
import com.example.meetingtranscriber.engine.EngineRouter
import com.example.meetingtranscriber.engine.EngineState
import com.example.meetingtranscriber.engine.AsrSentence
import com.example.meetingtranscriber.util.TextFormatter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class MeetingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val meetingRepository = MeetingRepository(db)
    private val transcriptRepository = TranscriptRepository(db)

    // ── 引擎路由 + UseCase（用于抽象 ASR/LLM 访问，而非直接调用 AsrProvider） ──
    private val engineRouter: EngineRouter by lazy {
        (getApplication<MeetingApplication>()).engineRouter
    }
    val transcriptionUseCase by lazy { TranscriptionUseCase(engineRouter) }
    val summaryUseCase by lazy { SummaryUseCase(engineRouter) }

    val audioCaptureManager = AudioCaptureManager()
    private val vadDetector = VADDetector()
    private var wavRecorder: WavRecorder? = null

    private val _uiState = MutableStateFlow(MeetingUiState())
    val uiState: StateFlow<MeetingUiState> = _uiState.asStateFlow()

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

    private var currentMeetingId: Long = 0
    private val speakerLabelMap = mutableMapOf<String, String>()
    private var meetingStartTime = 0L
    private var elapsedJob: Job? = null
    private var currentMeetingTitle: String = ""
    private val recoveryStateDao = db.recoveryStateDao()
    private var recoveryJob: Job? = null
    private val pendingSegmentsForRecovery = mutableListOf<TranscriptSegment>()

    /** 已处理的 ASR 句子数（避免重复 append） */
    private var processedSentenceCount = 0

    /** VAD 连续沉默帧计数器（用于说话人切换检测） */
    private var speakerGapFrames = 0
    /** 两个说话人之间的最小沉默帧数（CHUNK_MS=100ms → 20帧=2秒） */
    private val speakerGapThresholdFrames = 20

    init {
        // --- 真实模式：监听音频 → VAD 过滤（含说话人切换检测）→ EngineRouter ---
        viewModelScope.launch {
            audioCaptureManager.audioStream.collect { pcmData ->
                if (_uiState.value.isMeetingActive && !_uiState.value.isPaused) {
                    // 保存到本地 WAV + VAD + ASR 转写
                    wavRecorder?.write(pcmData)
                    val isVoice = vadDetector.isVoice(pcmData)
                    if (_uiState.value.isSpeaking != isVoice) {
                        _uiState.update { it.copy(isSpeaking = isVoice) }
                    }
                    if (isVoice) {
                        // 长时沉默后语音恢复 → 检测到新说话人
                        if (speakerGapFrames >= speakerGapThresholdFrames) {
                            transcriptionUseCase.switchSpeaker()
                            android.util.Log.d("MeetingViewModel",
                                "检测到说话人切换（沉默 ${speakerGapFrames * 100}ms）")
                        }
                        speakerGapFrames = 0
                        transcriptionUseCase.processAudio(pcmData)
                    } else {
                        // 防止计数溢出
                        if (speakerGapFrames < speakerGapThresholdFrames + 50) {
                            speakerGapFrames++
                        }
                    }
                }
            }
        }

        // ── TranscriptionUseCase 状态收集（替代 deprecated asrProvider 回调）──
        viewModelScope.launch {
            transcriptionUseCase.interimText.collect { text ->
                _uiState.update { it.copy(interimText = text) }
            }
        }

        viewModelScope.launch {
            transcriptionUseCase.sentences.collect { allSentences ->
                // 只处理新句子（增量追加）
                if (allSentences.size > processedSentenceCount) {
                    for (i in processedSentenceCount until allSentences.size) {
                        val s = allSentences[i]
                        appendSegment(s.speakerId, s.text, s.startTimeMs, s.endTimeMs, s.sentenceId)
                    }
                    processedSentenceCount = allSentences.size
                }
            }
        }

        viewModelScope.launch {
            transcriptionUseCase.engineStatus.collect { status ->
                val connState = when (status.state) {
                    EngineState.RUNNING -> ConnectionState.CONNECTED
                    EngineState.LOADING -> ConnectionState.CONNECTING
                    EngineState.ERROR -> ConnectionState.FAILED
                    EngineState.IDLE -> ConnectionState.DISCONNECTED
                    else -> ConnectionState.DISCONNECTED
                }
                _uiState.update { it.copy(
                    connectionState = connState,
                    isConnected = connState == ConnectionState.CONNECTED,
                    asrEngineStatus = status.state
                ) }
            }
        }

        viewModelScope.launch {
            transcriptionUseCase.lastError.collect { error ->
                _uiState.update { it.copy(errorMessage = error) }
            }
        }

        viewModelScope.launch {
            transcriptionUseCase.currentEngine.collect { engineType ->
                _uiState.update { it.copy(asrEngineName = engineType?.displayName ?: "") }
            }
        }

    }

    /** 在线/离线会议 — 引擎自动路由 */
    fun startMeeting(
        title: String = "会议_${System.currentTimeMillis()}",
        language: String = "cn",
        tag: String? = null,
        engineTypeOverride: com.example.meetingtranscriber.engine.AsrEngineType? = null
    ) {
        viewModelScope.launch {
            if (!audioCaptureManager.hasPermission()) {
                _uiState.update { it.copy(errorMessage = "缺少录音权限") }
                return@launch
            }

            currentMeetingTitle = title.ifBlank { "会议" }
            currentMeetingId = meetingRepository.createMeeting(title, tag)
            meetingStartTime = System.currentTimeMillis()

            val started = audioCaptureManager.start()
            if (!started) {
                _uiState.update { it.copy(errorMessage = "启动音频采集失败") }
                return@launch
            }

            // 同时启动本地 WAV 录音存档
            val app = getApplication<com.example.meetingtranscriber.MeetingApplication>()
            val recordingsDir = app.getExternalFilesDir("realtime_recordings") ?: app.filesDir
            recordingsDir.mkdirs()
            val wavFile = File(recordingsDir, "realtime_${currentMeetingId}_${System.currentTimeMillis()}.wav")
            val encKey = CryptoManager.getFileSecretKey()
            wavRecorder = WavRecorder(wavFile, encKey).also {
                if (!it.start()) {
                    android.util.Log.w("MeetingViewModel", "实时音频存档启动失败")
                    wavRecorder = null
                } else {
                    meetingRepository.updateAudioFilePath(currentMeetingId, wavFile.absolutePath)
                }
            }

            if (!connectAsr(language, engineTypeOverride)) return@launch

            _uiState.update { it.copy(
                isMeetingActive = true,
                isPaused = false,
                isSpeaking = false,
                errorMessage = null,
                selectedLanguage = language
            ) }
            startTimer()
            startRecoverySaver()
        }
    }

    /** 使用 TranscriptionUseCase → EngineRouter 智能路由启动 ASR */
    private suspend fun connectAsr(
        language: String = "cn",
        engineTypeOverride: com.example.meetingtranscriber.engine.AsrEngineType? = null
    ): Boolean {
        val vocab = db.vocabularyDao().getVocabularyForMeeting(currentMeetingId)
        val result = transcriptionUseCase.start(
            context = getApplication(),
            config = com.example.meetingtranscriber.engine.AsrConfig(
                language = language,
                vocabularyId = vocab?.vocabularyId
            ),
            engineTypeOverride = engineTypeOverride
        )
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: "创建转写任务失败"
            android.util.Log.e("MeetingViewModel", "ASR 启动失败: $msg")
            _uiState.update { it.copy(errorMessage = msg) }
            return false
        }
        processedSentenceCount = 0
        speakerGapFrames = 0
        vadDetector.reset()
        return true
    }

    /** 重试连接（网络恢复后手动触发） */
    fun retryConnection() {
        viewModelScope.launch {
            connectAsr(_uiState.value.selectedLanguage)
        }
    }

    /** 暂停/继续 */
    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    /** 结束会议 */
    fun endMeeting() {
        viewModelScope.launch {
            stopRecoverySaver()

            audioCaptureManager.stop()
            wavRecorder?.stop()
            wavRecorder = null
            transcriptionUseCase.stop()

            val meeting = meetingRepository.endMeeting(currentMeetingId)
            recoveryStateDao.delete(currentMeetingId)

            _uiState.update { it.copy(
                isMeetingActive = false,
                isPaused = false,
                interimText = "",
                isConnected = false
            ) }
            stopTimer()

            if (meeting != null) {
                generateSummary(meeting)
                segmentTopics(meeting)
            }
        }
    }

    fun loadSegments(meetingId: Long) {
        viewModelScope.launch {
            transcriptRepository.getSegments(meetingId).collect { list ->
                list.forEach { segment ->
                    if (!speakerLabelMap.containsKey(segment.speakerId)) {
                        speakerLabelMap[segment.speakerId] = segment.displaySpeaker
                    }
                }
                _segments.value = list
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun appendSegment(speakerId: String, text: String, startMs: Long, endMs: Long, sentenceId: Long) {
        val formattedText = TextFormatter.spokenNumberToDigits(TextFormatter.format(text))
        val label = speakerLabelMap.getOrPut(speakerId) {
            "会议人${speakerLabelMap.size + 1}"
        }
        val segment = TranscriptSegment(
            meetingId = currentMeetingId,
            speakerId = speakerId,
            displaySpeaker = label,
            text = formattedText,
            startTimeMs = startMs,
            endTimeMs = endMs,
            sentenceId = sentenceId
        )
        _segments.update { it + segment }
        pendingSegmentsForRecovery.add(segment)
        _uiState.update { it.copy(
            interimText = "",
            speakerLabels = speakerLabelMap.toMap(),
            speakerCount = speakerLabelMap.size
        ) }
        viewModelScope.launch {
            transcriptRepository.saveSegment(segment)
        }
    }

    private fun generateSummary(meeting: MeetingInfo) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isGeneratingSummary = true, summaryProgress = 0f) }

                // 收集摘要进度
                val progressJob = launch {
                    summaryUseCase.generationProgress.collect { progress ->
                        _uiState.update { it.copy(summaryProgress = progress) }
                    }
                }

                val segments = transcriptRepository.getSegmentsOnce(meeting.id)
                val fullText = segments.joinToString("\n") {
                    "${it.displaySpeaker} [${it.formattedTime}]: ${it.text}"
                }
                // 优先使用 SummaryUseCase（通过引擎路由），回退到 deprecated 规则生成器
                val summary = summaryUseCase.generate(
                    getApplication(), fullText, com.example.meetingtranscriber.engine.SummaryStyle.STANDARD
                ).getOrElse {
                    // Fallback: 所有云端 LLM 不可用且 Qwen 模型未下载时，
                    // 回退到内置规则生成器。删除时机：Qwen 模型覆盖 >90% 用户后。
                    @Suppress("DEPRECATION")
                    MeetingSummaryGenerator.generate(fullText)
                }
                meetingRepository.saveSummary(meeting.id, summary)

                progressJob.cancel()
                _uiState.update { it.copy(isGeneratingSummary = false, summaryProgress = 1f) }
            } catch (e: Exception) {
                android.util.Log.w("MeetingViewModel", "纪要生成失败: ${e.message}")
                _uiState.update { it.copy(isGeneratingSummary = false, summaryProgress = 0f) }
            }
        }
    }

    private fun segmentTopics(meeting: MeetingInfo) {
        viewModelScope.launch {
            try {
                val segments = transcriptRepository.getSegmentsOnce(meeting.id)
                val topicMap = com.example.meetingtranscriber.util.TopicSegmenter.segmentByTimeGap(segments)
                transcriptRepository.updateTopicIds(topicMap)
            } catch (e: Exception) {
                android.util.Log.w("MeetingViewModel", "话题分段失败: ${e.message}")
            }
        }
    }

    private fun startTimer() {
        elapsedJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (!_uiState.value.isPaused) {
                    val elapsed = ((System.currentTimeMillis() - meetingStartTime) / 1000).toInt()
                    _uiState.update { it.copy(elapsedSeconds = elapsed) }
                }
            }
        }
    }

    private fun stopTimer() {
        elapsedJob?.cancel()
        elapsedJob = null
    }

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.isMeetingActive) {
            stopRecoverySaver()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    withTimeout(2000L) { saveRecoveryState() }
                } catch (_: Exception) {}
            }
            audioCaptureManager.stop()
            viewModelScope.launch { transcriptionUseCase.cancel() }
            wavRecorder?.cancel()
            wavRecorder = null
        }
        stopTimer()
    }

    // ── 崩溃恢复 ──

    private fun startRecoverySaver() {
        recoveryJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                saveRecoveryState()
            }
        }
    }

    private fun stopRecoverySaver() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private suspend fun saveRecoveryState() {
        try {
            val state = RecoveryStateEntity(
                meetingId = currentMeetingId,
                title = currentMeetingTitle,
                startTime = meetingStartTime,
                isOfflineMode = false,
                isDemoMode = false,
                currentTaskId = "",  // TranscriptionUseCase 已封装引擎细节
                audioBufferFilePath = null,
                audioBufferFrameCount = 0,
                speakerLabelMapJson = Gson().toJson(speakerLabelMap.toMap()),
                pendingSegmentsJson = Gson().toJson(pendingSegmentsForRecovery.toList())
            )
            recoveryStateDao.upsert(state)
            pendingSegmentsForRecovery.clear()
        } catch (e: Exception) {
            android.util.Log.w("MeetingViewModel", "保存恢复状态失败: ${e.message}")
        }
    }

    /** 从崩溃恢复 */
    fun recoverFromCrash(state: RecoveryStateEntity) {
        viewModelScope.launch {
            currentMeetingId = state.meetingId
            currentMeetingTitle = state.title
            meetingStartTime = state.startTime

            val mapType = object : TypeToken<Map<String, String>>() {}.type
            speakerLabelMap.putAll(Gson().fromJson(state.speakerLabelMapJson, mapType))

            val listType = object : TypeToken<List<TranscriptSegment>>() {}.type
            val pendingSegments: List<TranscriptSegment> = Gson().fromJson(state.pendingSegmentsJson, listType)
            pendingSegmentsForRecovery.clear()

            _uiState.update { it.copy(
                isMeetingActive = true,
                isPaused = false,
                speakerLabels = speakerLabelMap.toMap(),
                speakerCount = speakerLabelMap.size
            ) }

            pendingSegments.forEach { transcriptRepository.saveSegment(it) }

            // 重新连接 ASR
            if (!audioCaptureManager.hasPermission()) {
                _uiState.update { it.copy(errorMessage = "恢复失败：缺少录音权限") }
                return@launch
            }
            val started = audioCaptureManager.start()
            if (!started) {
                _uiState.update { it.copy(errorMessage = "恢复音频采集失败") }
                return@launch
            }
            connectAsr(_uiState.value.selectedLanguage)
            // 回放溢出音频
            state.audioBufferFilePath?.let { path ->
                val overflowFile = File(path)
                if (overflowFile.exists()) {
                    WavPlayer.play(overflowFile).collect { pcmData ->
                        if (!_uiState.value.isMeetingActive) return@collect
                        transcriptionUseCase.processAudio(pcmData)
                    }
                    overflowFile.delete()
                }
            }

            startTimer()
            startRecoverySaver()
        }
    }
}
