package com.example.mt.ui.meeting

import com.example.mt.audio.VADDetector
import com.example.mt.audio.WavRecorder
import com.example.mt.config.EngineKeys
import com.example.mt.data.model.MeetingInfo
import com.example.mt.data.model.TranscriptSegment
import com.example.mt.data.repository.MeetingRepository
import com.example.mt.data.repository.TranscriptRepository
import com.example.mt.domain.SummaryUseCase
import com.example.mt.domain.TranscriptionUseCase
import com.example.mt.engine.*
import com.example.mt.network.ConnectionState
import com.example.mt.platform.*
import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.sqrt

/**
 * 跨平台会议 ViewModel — 管理完整的会议录制、转写、纪要生成生命周期。
 *
 * 注入所有平台抽象和用例，在 commonMain 中即可运行核心逻辑，
 * Desktop / Android 各自提供 Compose UI 绑定到 [uiState] 和 [transcriptSegments]。
 */
class MeetingViewModel(
    private val engineKeys: EngineKeys,
    private val transcriptionUseCase: TranscriptionUseCase,
    private val summaryUseCase: SummaryUseCase,
    private val meetingRepo: MeetingRepository,
    private val transcriptRepo: TranscriptRepository,
    private val audioCapture: PlatformAudioCapture,
    private val fileAccess: FileAccess,
    private val voiceprintLoader: VoiceprintModelLoader,
    private val networkMonitor: NetworkMonitor,
    private val wavRecorder: WavRecorder,
) {
    // ═══════════════════════════════════════════════════
    // 公开 StateFlow
    // ═══════════════════════════════════════════════════

    private val _uiState = MutableStateFlow(MeetingUiState())
    val uiState: StateFlow<MeetingUiState> = _uiState

    private val _transcriptSegments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val transcriptSegments: StateFlow<List<TranscriptSegment>> = _transcriptSegments

    // ═══════════════════════════════════════════════════
    // 内部状态
    // ═══════════════════════════════════════════════════

    private var meetingScope: CoroutineScope? = null
    private var currentMeetingId: Long = 0L
    private var wavFilePath: String = ""
    private var audioCollectionJob: Job? = null
    private var sentenceCollectionJob: Job? = null
    private var recoveryJob: Job? = null
    private var timerJob: Job? = null

    private val vadDetector = VADDetector()
    private val speakerEmbeddings = mutableMapOf<String, FloatArray>()
    private var nextSpeakerIndex = 0
    private var currentSentenceId = 0L
    private var currentSegmentId = 0L
    private var startTimeMs = 0L

    // 记录各 speaker 的最后发言时间，用于 VAD 断句后的 speaker 分配
    private val speakerLastSeen = mutableMapOf<String, Long>()

    // ═══════════════════════════════════════════════════
    // 会议生命周期
    // ═══════════════════════════════════════════════════

    /** 加载声纹识别模型（UI 层在页面初始化时调用） */
    fun loadVoiceprintModel() {
        if (!voiceprintLoader.isLoaded) {
            voiceprintLoader.load()
        }
    }

    /**
     * 开始新会议（可在主线程调用，阻塞操作在后台执行）。
     * @param title 会议标题
     * @param language 识别语言代码（cn / yue / en / cn-en）
     */
    fun startMeeting(title: String, language: String = "cn") {
        if (_uiState.value.isMeetingActive) {
            Napier.w("MeetingViewModel: 会议已在运行中")
            return
        }

        // 立即更新 UI 状态（非阻塞）
        _uiState.update { state ->
            state.copy(
                isMeetingActive = true,
                isPaused = false,
                elapsedSeconds = 0,
                selectedLanguage = language,
                speakerLabels = emptyMap(),
                speakerCount = 0,
                errorMessage = null,
                asrEngineStatus = EngineState.LOADING,
                connectionState = ConnectionState.CONNECTING,
            )
        }

        meetingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // 所有阻塞操作在后台协程中执行
        meetingScope!!.launch {
            try {
                // 1. 创建会议记录（DB 写入）
                val now = System.currentTimeMillis()
                val meeting = MeetingInfo(title = title, dialectUsed = language, startTime = now)
                currentMeetingId = meetingRepo.insert(meeting)
                startTimeMs = now

                // 2. 创建 WAV 文件路径
                wavFilePath = "${fileAccess.getAudioDir()}/${currentMeetingId}_${now}.wav"

                // 3. 启动录音（文件 I/O）
                if (!wavRecorder.start(wavFilePath)) {
                    _uiState.update { it.copy(errorMessage = "无法创建录音文件", isMeetingActive = false) }
                    return@launch
                }

                // 4. 启动音频采集（可能阻塞 ~2s 初始化麦克风）
                if (!audioCapture.start()) {
                    _uiState.update { it.copy(errorMessage = "无法访问麦克风", isMeetingActive = false) }
                    wavRecorder.stop()
                    return@launch
                }

                // 5. 启动 ASR
                val config = AsrConfig(language = language)
                val result = transcriptionUseCase.start(config)
                if (result.isFailure) {
                    val msg = "ASR 启动失败: ${result.exceptionOrNull()?.message}"
                    Napier.e("MeetingViewModel: $msg")
                    _uiState.update { it.copy(errorMessage = msg, isMeetingActive = false) }
                    audioCapture.stop()
                    wavRecorder.stop()
                    return@launch
                }

                Napier.i("MeetingViewModel: 会议已开始 (id=$currentMeetingId, title=$title)")
            } catch (e: Exception) {
                Napier.e("MeetingViewModel: 启动会议异常", e)
                _uiState.update { it.copy(errorMessage = "启动失败: ${e.message}", isMeetingActive = false) }
            }
        }

        // 6. 开始音频管线
        audioCollectionJob = meetingScope!!.launch {
            audioCapture.audioStream.collect { pcmData ->
                wavRecorder.write(pcmData)
                if (!_uiState.value.isPaused) {
                    transcriptionUseCase.processAudio(pcmData)
                }
                val voice = vadDetector.isVoice(pcmData)
                _uiState.update { it.copy(isSpeaking = voice) }
                if (voice && voiceprintLoader.isLoaded) {
                    val embedding = voiceprintLoader.extractEmbedding(pcmData)
                    if (embedding != null) identifySpeaker(embedding)
                }
            }
        }

        // 7. 收集转写句子
        sentenceCollectionJob = meetingScope!!.launch {
            transcriptionUseCase.sentenceFlow.collect { sentence ->
                currentSentenceId = sentence.sentenceId
                val segment = TranscriptSegment(
                    meetingId = currentMeetingId,
                    speakerId = sentence.speakerId,
                    displaySpeaker = _uiState.value.speakerLabels[sentence.speakerId] ?: sentence.speakerId,
                    text = sentence.text,
                    startTimeMs = sentence.startTimeMs,
                    endTimeMs = sentence.endTimeMs,
                    sentenceId = sentence.sentenceId,
                    isInterim = false,
                )
                currentSegmentId = transcriptRepo.insert(segment)
                _transcriptSegments.update { it + segment }
                val speakerCount = _transcriptSegments.value.map { it.speakerId }.distinct().size
                _uiState.update { it.copy(speakerCount = speakerCount) }
                speakerLastSeen[sentence.speakerId] = sentence.endTimeMs
            }
        }

        // 8. 收集 ASR 状态
        meetingScope!!.launch {
            transcriptionUseCase.engineStatus.collect { status ->
                _uiState.update { it.copy(asrEngineStatus = status.state) }
            }
        }
        meetingScope!!.launch {
            transcriptionUseCase.currentEngine.collect { engineType ->
                _uiState.update { it.copy(asrEngineName = engineType?.displayName ?: "") }
            }
        }
        meetingScope!!.launch {
            transcriptionUseCase.interimText.collect { text ->
                _uiState.update { it.copy(interimText = text) }
            }
        }

        // 9. 网络状态监控
        meetingScope!!.launch {
            networkMonitor.networkState.collect { online ->
                val cs = if (online) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
                _uiState.update { it.copy(connectionState = cs, isConnected = online) }
            }
        }

        // 10. 计时器
        timerJob = meetingScope!!.launch {
            while (isActive) {
                delay(1000)
                _uiState.update {
                    it.copy(elapsedSeconds = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt())
                }
            }
        }

        // 11. 崩溃恢复（每 5 秒）
        recoveryJob = meetingScope!!.launch {
            while (isActive) {
                delay(5000)
                meetingRepo.saveRecoveryState(
                    meetingId = currentMeetingId,
                    lastSentenceId = currentSentenceId,
                    lastSegmentId = currentSegmentId,
                    snapshot = _transcriptSegments.value.joinToString("\n") { seg ->
                        "${seg.speakerId}|${escapeDelimiter(seg.displaySpeaker)}|${escapeDelimiter(seg.text)}|${seg.startTimeMs}|${seg.endTimeMs}"
                    },
                )
            }
        }
    }

    /** 暂停转写（继续录音，停止推送到 ASR） */
    fun pauseMeeting() {
        _uiState.update { it.copy(isPaused = true) }
        Napier.i("MeetingViewModel: 会议已暂停")
    }

    /** 恢复转写 */
    fun resumeMeeting() {
        _uiState.update { it.copy(isPaused = false) }
        Napier.i("MeetingViewModel: 会议已恢复")
    }

    /** 结束会议，生成纪要 */
    fun endMeeting() {
        if (!_uiState.value.isMeetingActive) return

        meetingScope?.launch {
            try {
                _uiState.update { it.copy(isGeneratingSummary = true, summaryProgress = 0f) }

                // 1. 停止音频采集和录音
                audioCapture.stop()
                audioCollectionJob?.cancel()
                timerJob?.cancel()
                recoveryJob?.cancel()

                wavRecorder.stop()
                val endTime = System.currentTimeMillis()

                // 2. 停止 ASR，获取所有句子
                val sentences = transcriptionUseCase.stop()
                val speakerCount = sentences.map { it.speakerId }.distinct().size

                // 3. 更新音频路径
                meetingRepo.updateAudioPath(currentMeetingId, wavFilePath)

                // 4. 完成会议记录
                val durationSeconds = ((endTime - startTimeMs) / 1000).toInt()
                meetingRepo.finishMeeting(
                    id = currentMeetingId,
                    endTime = endTime,
                    durationSeconds = durationSeconds,
                    speakerCount = speakerCount,
                    segmentCount = _transcriptSegments.value.size,
                )

                // 5. 清理恢复状态
                meetingRepo.deleteRecoveryState(currentMeetingId)

                // 6. 生成纪要
                val transcriptText = _transcriptSegments.value.joinToString("\n") { seg ->
                    "${seg.displaySpeaker} (${seg.formattedTime}): ${seg.text}"
                }

                var actualLlmEngine: String = "RULE_BASED"
                val summary = if (engineKeys.hasAnyCloudLlmKey()) {
                    // 云端 LLM
                    _uiState.update { it.copy(summaryProgress = 0.3f) }
                    val result = summaryUseCase.generate(transcriptText, engineKeys.summaryStyle)
                    result.getOrElse {
                        // LLM 失败 → 降级到规则兜底
                        Napier.w("MeetingViewModel: LLM 纪要失败，降级到规则摘要: ${it.message}")
                        MeetingSummaryGenerator.generate(transcriptText)
                    }.also { generated ->
                        if (result.isSuccess) actualLlmEngine = engineKeys.preferredLlmEngine.name
                    }
                } else {
                    // 直接规则兜底
                    MeetingSummaryGenerator.generate(transcriptText)
                }

                // 7. 保存纪要（记录实际使用的引擎）
                meetingRepo.updateSummary(currentMeetingId, summary, actualLlmEngine)

                _uiState.update { state ->
                    state.copy(
                        isMeetingActive = false,
                        isGeneratingSummary = false,
                        summaryProgress = 1f,
                        latestSummary = summary,
                        summaryMeetingId = currentMeetingId,
                        showSummaryReviewDialog = true,
                    )
                }

                Napier.i("MeetingViewModel: 会议已结束 (id=$currentMeetingId, speakers=$speakerCount)")
            } catch (e: Exception) {
                Napier.e("MeetingViewModel: 结束会议异常", e)
                _uiState.update {
                    it.copy(
                        isMeetingActive = false,
                        isGeneratingSummary = false,
                        errorMessage = "结束会议失败: ${e.message}",
                    )
                }
            } finally {
                sentenceCollectionJob?.cancel()
                meetingScope?.cancel()
                meetingScope = null
            }
        }
    }

    /** 取消会议（不保存） */
    fun cancelMeeting() {
        meetingScope?.launch {
            audioCapture.stop()
            audioCollectionJob?.cancel()
            timerJob?.cancel()
            recoveryJob?.cancel()
            sentenceCollectionJob?.cancel()
            wavRecorder.stop()
            transcriptionUseCase.cancel()

            // 清理数据库
            transcriptRepo.deleteByMeeting(currentMeetingId)
            meetingRepo.deleteRecoveryState(currentMeetingId)
            meetingRepo.delete(currentMeetingId)

            _transcriptSegments.value = emptyList()
            _uiState.value = MeetingUiState()

            meetingScope?.cancel()
            meetingScope = null
        }
        Napier.i("MeetingViewModel: 会议已取消")
    }

    /** 保存用户编辑后的纪要并关闭弹窗 */
    fun saveEditedSummary(edited: String) {
        meetingRepo.updateSummary(currentMeetingId, edited, engineKeys.preferredLlmEngine.name)
        _uiState.update { it.copy(showSummaryReviewDialog = false, latestSummary = edited) }
    }

    /** 关闭纪要审核弹窗（不保存编辑） */
    fun dismissSummaryDialog() {
        _uiState.update { it.copy(showSummaryReviewDialog = false) }
    }

    /** 更新会议标题 */
    fun updateTitle(title: String) {
        if (currentMeetingId > 0) {
            meetingRepo.updateTitle(currentMeetingId, title)
        }
    }

    /** 切换识别语言 */
    fun switchLanguage(languageCode: String) {
        _uiState.update { it.copy(selectedLanguage = languageCode) }
        meetingScope?.launch {
            transcriptionUseCase.switchLanguage(languageCode)
        }
    }

    // ═══════════════════════════════════════════════════
    // 声纹识别（内部）
    // ═══════════════════════════════════════════════════

    private fun identifySpeaker(embedding: FloatArray) {
        // 与已知 speaker 做余弦相似度匹配
        var bestMatch: String? = null
        var bestScore = 0.6f // 阈值

        for ((speakerId, known) in speakerEmbeddings) {
            val score = cosineSimilarity(embedding, known)
            if (score > bestScore) {
                bestScore = score
                bestMatch = speakerId
            }
        }

        if (bestMatch == null) {
            // 新 speaker
            nextSpeakerIndex++
            val newSpeakerId = "speaker_$nextSpeakerIndex"
            val displayName = "发言人$nextSpeakerIndex"
            speakerEmbeddings[newSpeakerId] = embedding

            val labels = _uiState.value.speakerLabels.toMutableMap()
            labels[newSpeakerId] = displayName
            _uiState.update { it.copy(speakerLabels = labels) }
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    // ═══════════════════════════════════════════════════
    // 清理
    // ═══════════════════════════════════════════════════

    fun dispose() {
        meetingScope?.cancel()
        meetingScope = null
        audioCapture.stop()
        try { wavRecorder.stop() } catch (_: Exception) {}
        try { voiceprintLoader.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MeetingViewModel"

        /**
         * 对恢复快照中包含的分隔符进行转义。
         * 先转义反斜杠，再转义竖线，以确保快照行能被正确解析。
         */
        internal fun escapeDelimiter(s: String): String =
            s.replace("\\", "\\\\").replace("|", "\\|")

        /**
         * 反转义 — 与 [escapeDelimiter] 配对使用。
         * 注意：必须先还原竖线再还原反斜杠。
         */
        internal fun unescapeDelimiter(s: String): String =
            s.replace("\\|", "|").replace("\\\\", "\\")
    }
}
