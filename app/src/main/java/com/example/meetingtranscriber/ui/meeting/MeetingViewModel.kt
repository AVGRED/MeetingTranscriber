package com.example.meetingtranscriber.ui.meeting

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.audio.AudioCaptureManager
import com.example.meetingtranscriber.audio.DemoAsrSimulator
import com.example.meetingtranscriber.audio.VADDetector
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.data.model.TranscriptSegment
import com.example.meetingtranscriber.data.repository.MeetingRepository
import com.example.meetingtranscriber.data.repository.TranscriptRepository
import com.example.meetingtranscriber.network.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MeetingUiState(
    val isMeetingActive: Boolean = false,
    val isPaused: Boolean = false,
    val isConnected: Boolean = false,
    val isDemoMode: Boolean = false,
    val elapsedSeconds: Int = 0,
    val connectionState: AsrWebSocketClient.ConnectionState = AsrWebSocketClient.ConnectionState.DISCONNECTED,
    val interimText: String = "",
    val speakerLabels: Map<String, String> = emptyMap(),
    val speakerCount: Int = 0,
    val errorMessage: String? = null
)

class MeetingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val meetingRepository = MeetingRepository(db)
    private val transcriptRepository = TranscriptRepository(db)

    val audioCaptureManager = AudioCaptureManager()
    private val asrClient = AsrWebSocketClient()
    private val tingwuApi = TingwuApiClient()
    private val authManager = AuthTokenManager()
    private val vadDetector = VADDetector()
    private val demoSimulator = DemoAsrSimulator()

    private val _uiState = MutableStateFlow(MeetingUiState())
    val uiState: StateFlow<MeetingUiState> = _uiState.asStateFlow()

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

    private var currentMeetingId: Long = 0
    private var currentTaskId: String = ""
    private val speakerLabelMap = mutableMapOf<String, String>()
    private var meetingStartTime = 0L
    private var elapsedJob: kotlinx.coroutines.Job? = null

    init {
        // --- 真实模式：监听音频 → 发送到云端 ASR ---
        viewModelScope.launch {
            audioCaptureManager.audioStream.collect { pcmData ->
                if (_uiState.value.isMeetingActive && !_uiState.value.isPaused && !_uiState.value.isDemoMode) {
                    asrClient.sendAudio(pcmData)
                }
            }
        }

        // 真实 ASR：中间结果（实时出字）
        asrClient.onInterimResult = { text ->
            if (!_uiState.value.isDemoMode) {
                _uiState.update { it.copy(interimText = text) }
            }
        }

        // 真实 ASR：完整句子
        asrClient.onSentenceResult = { result ->
            if (!_uiState.value.isDemoMode) {
                appendSegment(result.speakerId, result.text, result.startTimeMs, result.endTimeMs, result.sentenceId)
            }
        }

        // 真实 ASR：连接状态
        asrClient.onConnectionStateChanged = { state ->
            _uiState.update { it.copy(connectionState = state, isConnected = state == AsrWebSocketClient.ConnectionState.CONNECTED) }
        }

        // 真实 ASR：错误处理
        asrClient.onError = { error ->
            _uiState.update { it.copy(errorMessage = error) }
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
                    // 演示结束，自动触发结束流程
                    onDemoFinished()
                }
            }
        }
    }

    /** 演示模式 — 不需要权限、不需要 API Key */
    fun startDemo() {
        viewModelScope.launch {
            val title = "演示会议_${java.text.SimpleDateFormat("MM-dd_HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
            currentMeetingId = meetingRepository.createMeeting(title)
            meetingStartTime = System.currentTimeMillis()

            _uiState.update { it.copy(
                isMeetingActive = true,
                isPaused = false,
                isDemoMode = true,
                isConnected = true,
                errorMessage = null,
                speakerLabels = emptyMap(),
                speakerCount = 0,
                elapsedSeconds = 0,
                interimText = ""
            ) }
            _segments.value = emptyList()
            speakerLabelMap.clear()

            startTimer()
            demoSimulator.start()
        }
    }

    /** 真实模式 — 需要录音权限 + API Key */
    fun startMeeting(title: String = "会议_${System.currentTimeMillis()}") {
        viewModelScope.launch {
            if (!audioCaptureManager.hasPermission()) {
                _uiState.update { it.copy(errorMessage = "缺少录音权限") }
                return@launch
            }

            currentMeetingId = meetingRepository.createMeeting(title)
            meetingStartTime = System.currentTimeMillis()

            val token = authManager.getToken()
            if (token == null) {
                _uiState.update { it.copy(errorMessage = "鉴权失败，请检查 AccessKey 配置。\n可切换到演示模式体验功能。") }
                return@launch
            }

            val taskResult = tingwuApi.createRealtimeTask(accessToken = token)
            if (taskResult == null || !taskResult.isValid) {
                _uiState.update { it.copy(errorMessage = "创建转写任务失败，请检查网络和应用配置") }
                return@launch
            }
            currentTaskId = taskResult.taskId

            val started = audioCaptureManager.start()
            if (!started) {
                _uiState.update { it.copy(errorMessage = "启动音频采集失败") }
                return@launch
            }

            asrClient.connect(taskResult.meetingJoinUrl, taskResult.taskId)

            _uiState.update { it.copy(
                isMeetingActive = true,
                isPaused = false,
                isDemoMode = false,
                errorMessage = null
            ) }
            startTimer()
        }
    }

    /** 暂停/继续 */
    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    /** 结束会议 */
    fun endMeeting() {
        viewModelScope.launch {
            val isDemo = _uiState.value.isDemoMode

            if (isDemo) {
                demoSimulator.stop()
            } else {
                audioCaptureManager.stop()
                asrClient.disconnect()
                if (currentTaskId.isNotBlank()) {
                    val token = authManager.getToken()
                    if (token != null) {
                        try { tingwuApi.stopTask(currentTaskId, token) } catch (_: Exception) {}
                    }
                }
            }

            val meeting = meetingRepository.endMeeting(currentMeetingId)

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
            }
        }
    }

    /** 演示模式播放完毕后自动触发 */
    private fun onDemoFinished() {
        viewModelScope.launch {
            val meeting = meetingRepository.endMeeting(currentMeetingId)
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
        val label = speakerLabelMap.getOrPut(speakerId) {
            "会议人${speakerLabelMap.size + 1}"
        }
        val segment = TranscriptSegment(
            meetingId = currentMeetingId,
            speakerId = speakerId,
            displaySpeaker = label,
            text = text,
            startTimeMs = startMs,
            endTimeMs = endMs,
            sentenceId = sentenceId
        )
        _segments.update { it + segment }
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
                val segments = transcriptRepository.getSegmentsOnce(meeting.id)
                val fullText = segments.joinToString("\n") {
                    "${it.displaySpeaker} [${it.formattedTime}]: ${it.text}"
                }
                val summary = MeetingSummaryGenerator.generate(fullText)
                meetingRepository.saveSummary(meeting.id, summary)
            } catch (e: Exception) {
                android.util.Log.w("MeetingViewModel", "纪要生成失败: ${e.message}")
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
            audioCaptureManager.stop()
            asrClient.disconnect()
            demoSimulator.stop()
        }
        stopTimer()
    }
}
