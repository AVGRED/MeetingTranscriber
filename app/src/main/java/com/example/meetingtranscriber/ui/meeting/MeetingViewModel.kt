package com.example.meetingtranscriber.ui.meeting

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.audio.AudioCaptureManager
import com.example.meetingtranscriber.audio.DemoAsrSimulator
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
    private val demoSimulator = DemoAsrSimulator()
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

    init {
        // --- 真实模式：监听音频 → VAD 过滤 → TranscriptionUseCase → EngineRouter ---
        viewModelScope.launch {
            audioCaptureManager.audioStream.collect { pcmData ->
                if (_uiState.value.isMeetingActive && !_uiState.value.isPaused && !_uiState.value.isDemoMode) {
                    if (_uiState.value.isOfflineMode) {
                        wavRecorder?.write(pcmData)
                        if (!_uiState.value.isSpeaking) {
                            _uiState.update { it.copy(isSpeaking = true) }
                        }
                    } else {
                        // 实时模式：同时保存到本地 WAV
                        if (!_uiState.value.isUploading) {
                            wavRecorder?.write(pcmData)
                        }
                        val isVoice = vadDetector.isVoice(pcmData)
                        if (_uiState.value.isSpeaking != isVoice) {
                            _uiState.update { it.copy(isSpeaking = isVoice) }
                        }
                        if (isVoice) {
                            transcriptionUseCase.processAudio(pcmData)
                        }
                    }
                }
            }
        }

        // ── TranscriptionUseCase 状态收集（替代 deprecated asrProvider 回调）──
        viewModelScope.launch {
            transcriptionUseCase.interimText.collect { text ->
                if (!_uiState.value.isDemoMode) {
                    _uiState.update { it.copy(interimText = text) }
                }
            }
        }

        viewModelScope.launch {
            transcriptionUseCase.sentences.collect { allSentences ->
                if (_uiState.value.isDemoMode) return@collect
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
                if (!_uiState.value.isDemoMode) {
                    _uiState.update { it.copy(errorMessage = error) }
                }
            }
        }

        viewModelScope.launch {
            transcriptionUseCase.currentEngine.collect { engineType ->
                _uiState.update { it.copy(asrEngineName = engineType?.displayName ?: "") }
            }
        }

        // --- 演示模式：模拟转写 ---
        demoSimulator.onInterimResult = { text ->
            if (_uiState.value.isDemoMode) {
                _uiState.update { it.copy(interimText = text) }
            }
        }

        demoSimulator.onSentenceResult = { sentenceId, text, speakerId, startMs, endMs ->
            if (_uiState.value.isDemoMode) {
                appendSegment(speakerId, text, startMs, endMs, sentenceId)
            }
        }

        demoSimulator.onConnectionStateChanged = { state ->
            if (_uiState.value.isDemoMode) {
                val connected = state == 2
                _uiState.update { it.copy(isConnected = connected) }
                if (state == 0) {
                    onDemoFinished()
                }
            }
        }
    }

    /** 演示模式 — 不需要权限、不需要 API Key */
    fun startDemo(title: String = "演示会议") {
        viewModelScope.launch {
            val finalTitle = title.ifBlank { "演示会议" }
            currentMeetingTitle = finalTitle
            currentMeetingId = meetingRepository.createMeeting(finalTitle)
            meetingStartTime = System.currentTimeMillis()

            _uiState.update { it.copy(
                isMeetingActive = true,
                isPaused = false,
                isDemoMode = true,
                isConnected = true,
                isSpeaking = false,
                errorMessage = null,
                speakerLabels = emptyMap(),
                speakerCount = 0,
                elapsedSeconds = 0,
                interimText = ""
            ) }
            _segments.value = emptyList()
            speakerLabelMap.clear()
            pendingSegmentsForRecovery.clear()
            vadDetector.reset()

            startTimer()
            startRecoverySaver()
            demoSimulator.start()
        }
    }

    /** 真实模式 — 需要录音权限 + API Key */
    fun startMeeting(title: String = "会议_${System.currentTimeMillis()}", language: String = "cn", tag: String? = null) {
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

            if (!connectAsr(language)) return@launch

            _uiState.update { it.copy(
                isMeetingActive = true,
                isPaused = false,
                isDemoMode = false,
                isSpeaking = false,
                errorMessage = null,
                selectedLanguage = language
            ) }
            startTimer()
            startRecoverySaver()
        }
    }

    /** 离线录音 — 无需网络，音频保存为本地 WAV */
    fun startOfflineMeeting(title: String, language: String = "cn", tag: String? = null) {
        viewModelScope.launch {
            if (!audioCaptureManager.hasPermission()) {
                _uiState.update { it.copy(errorMessage = "缺少录音权限") }
                return@launch
            }

            currentMeetingTitle = title.ifBlank { "离线会议" }
            currentMeetingId = meetingRepository.createOfflineMeeting(
                title = title.ifBlank { "离线会议" },
                startTime = System.currentTimeMillis(),
                tag = tag
            )

            val file = File(
                getApplication<com.example.meetingtranscriber.MeetingApplication>().getExternalFilesDir(null),
                "offline_${currentMeetingId}.wav"
            )
            val encKey = CryptoManager.getFileSecretKey()
            wavRecorder = WavRecorder(file, encKey).also {
                if (!it.start()) {
                    _uiState.update { state -> state.copy(errorMessage = "创建录音文件失败") }
                    return@launch
                }
            }
            meetingRepository.updateAudioFilePath(currentMeetingId, file.absolutePath)

            val started = audioCaptureManager.start()
            if (!started) {
                wavRecorder?.cancel()
                wavRecorder = null
                _uiState.update { it.copy(errorMessage = "启动音频采集失败") }
                return@launch
            }

            meetingStartTime = System.currentTimeMillis()
            _uiState.update { it.copy(
                isMeetingActive = true,
                isPaused = false,
                isOfflineMode = true,
                isDemoMode = false,
                isSpeaking = false,
                errorMessage = null,
                selectedLanguage = language
            ) }
            startTimer()
            startRecoverySaver()
        }
    }

    /** 上传离线录音到云端转写 */
    fun uploadOfflineMeeting(meetingId: Long) {
        viewModelScope.launch {
            val meeting = meetingRepository.getMeeting(meetingId) ?: return@launch
            val path = meeting.audioFilePath ?: return@launch
            val file = File(path)
            if (!file.exists()) {
                _uiState.update { it.copy(errorMessage = "录音文件不存在") }
                return@launch
            }

            currentMeetingId = meetingId
            if (!connectAsr(_uiState.value.selectedLanguage)) return@launch

            _uiState.update { it.copy(isUploading = true, isMeetingActive = true, isOfflineMode = false, errorMessage = null) }
            meetingStartTime = System.currentTimeMillis()
            startTimer()

            // 等待 WebSocket 连接成功
            val connected = withTimeoutOrNull(10000L) {
                uiState.first { it.connectionState == ConnectionState.CONNECTED }
            }

            if (connected == null) {
                _uiState.update { it.copy(errorMessage = "上传失败：无法连接转写服务", isUploading = false) }
                return@launch
            }

            // 回放 WAV 文件到 ASR
            val encKey = CryptoManager.getFileSecretKey()
            WavPlayer.play(file, encKey).collect { pcmData ->
                if (!_uiState.value.isMeetingActive) return@collect
                transcriptionUseCase.processAudio(pcmData)
            }

            // 回放完毕，结束会议
            delay(2000)
            transcriptionUseCase.stop()

            val updated = meetingRepository.endMeeting(meetingId)
            stopRecoverySaver()
            recoveryStateDao.delete(meetingId)
            _uiState.update { it.copy(
                isMeetingActive = false,
                isOfflineMode = false,
                isUploading = false,
                interimText = ""
            ) }
            stopTimer()

            if (updated != null) generateSummary(updated)
        }
    }

    /** 使用 TranscriptionUseCase → EngineRouter 智能路由启动 ASR */
    private suspend fun connectAsr(language: String = "cn"): Boolean {
        val vocab = db.vocabularyDao().getVocabularyForMeeting(currentMeetingId)
        val result = transcriptionUseCase.start(
            context = getApplication(),
            config = com.example.meetingtranscriber.engine.AsrConfig(
                language = language,
                vocabularyId = vocab?.vocabularyId
            )
        )
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: "创建转写任务失败"
            android.util.Log.e("MeetingViewModel", "ASR 启动失败: $msg")
            _uiState.update { it.copy(errorMessage = msg) }
            return false
        }
        processedSentenceCount = 0
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
            val isDemo = _uiState.value.isDemoMode

            if (isDemo) {
                demoSimulator.stop()
            } else if (_uiState.value.isOfflineMode) {
                audioCaptureManager.stop()
                wavRecorder?.stop()
                wavRecorder = null
            } else {
                audioCaptureManager.stop()
                wavRecorder?.stop()
                wavRecorder = null
                transcriptionUseCase.stop()
            }

            val wasOffline = _uiState.value.isOfflineMode
            val meeting = meetingRepository.endMeeting(currentMeetingId)
            recoveryStateDao.delete(currentMeetingId)

            _uiState.update { it.copy(
                isMeetingActive = false,
                isPaused = false,
                interimText = "",
                isConnected = false,
                isDemoMode = false,
                isOfflineMode = false,
                showOfflineUploadPrompt = wasOffline
            ) }
            stopTimer()

            if (meeting != null && !meeting.isOffline) {
                generateSummary(meeting)
                segmentTopics(meeting)
            }
        }
    }

    /** 演示模式播放完毕后自动触发 */
    private fun onDemoFinished() {
        viewModelScope.launch {
            stopRecoverySaver()
            val meeting = meetingRepository.endMeeting(currentMeetingId)
            recoveryStateDao.delete(currentMeetingId)
            _uiState.update { it.copy(
                isMeetingActive = false,
                isPaused = false,
                interimText = "",
                isConnected = false,
                isDemoMode = false
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

    fun dismissUploadPrompt() {
        _uiState.update { it.copy(showOfflineUploadPrompt = false) }
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
            demoSimulator.stop()
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
                isOfflineMode = _uiState.value.isOfflineMode,
                isDemoMode = _uiState.value.isDemoMode,
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
                isDemoMode = state.isDemoMode,
                isOfflineMode = state.isOfflineMode,
                speakerLabels = speakerLabelMap.toMap(),
                speakerCount = speakerLabelMap.size
            ) }

            pendingSegments.forEach { transcriptRepository.saveSegment(it) }

            if (!state.isDemoMode) {
                if (!state.isOfflineMode) {
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
                }
            }

            startTimer()
            startRecoverySaver()
        }
    }
}
