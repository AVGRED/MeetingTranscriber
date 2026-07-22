package com.example.meetingtranscriber.ui.meeting

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingtranscriber.audio.AudioCaptureManager
import com.example.meetingtranscriber.audio.VADDetector
import com.example.meetingtranscriber.audio.VoiceprintIdentifier
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

    // DB 懒加载：getInstance 首调可能触发 SQLCipher 打开/迁移，不进主线程构造函数
    private val db by lazy { AppDatabase.getInstance(application) }
    private val meetingRepository by lazy { MeetingRepository(db) }
    private val transcriptRepository by lazy { TranscriptRepository(db) }

    // ── 引擎路由 + UseCase（用于抽象 ASR/LLM 访问，而非直接调用 AsrProvider） ──
    private val engineRouter: EngineRouter by lazy {
        (getApplication<MeetingApplication>()).engineRouter
    }
    val transcriptionUseCase by lazy { TranscriptionUseCase(engineRouter) }
    val summaryUseCase by lazy { SummaryUseCase(engineRouter) }

    val audioCaptureManager = AudioCaptureManager()
    private val vadDetector = VADDetector()  // 能量 VAD，仅用于 UI isSpeaking 指示
    private var wavRecorder: WavRecorder? = null

    private val _uiState = MutableStateFlow(MeetingUiState())
    val uiState: StateFlow<MeetingUiState> = _uiState.asStateFlow()

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

    /** 防止快速双击导致重复启动会议 */
    @Volatile private var isStartingMeeting = false
    private var currentMeetingId: Long = 0
    private val speakerLabelMap = mutableMapOf<String, String>()
    private var meetingStartTime = 0L
    private var elapsedJob: Job? = null
    private var currentMeetingTitle: String = ""
    private val recoveryStateDao by lazy { db.recoveryStateDao() }
    private var recoveryJob: Job? = null
    private val pendingSegmentsForRecovery = mutableListOf<TranscriptSegment>()
    /** 复用实例：Gson() 构造含反射 TypeAdapter 构建，每 5s 新建是无谓开销 */
    private val gson = Gson()

    /** 最近一条落地句子的引擎 sentenceId。合并后的段落保留首段 sentenceId，
     *  三段以上超长句的连续性判断必须用它而不是 last.sentenceId */
    private var lastAppendedSentenceId = -1L

    // ── 声纹识别（仅 Volcengine/豆包 模式启用） ──
    private var voiceprintIdentifier: VoiceprintIdentifier? = null

    // ── 时间轴：帧计数（每帧约 100ms，由 AudioCaptureManager 帧长决定） ──
    private var frameCount = 0L

    // ── VAD 切轮状态（用于声纹模式下的轮次切分） ──
    private var wasVoice = false
    private var currentTurnId = ""
    private var turnCounter = 0
    private var currentTurnStartMs = 0L

    // 注：turnSpeakerMap / turnTimeRanges / pendingSentences 跨线程读写
    // （IO 线程 processFrame+handleVolcengineSentence ↔ voiceprint 线程 onSpeakerIdentified），
    // 所有访问均通过互斥锁保护，避免 ConcurrentModificationException。
    private val voiceprintLock = Any()

    /** 轮次 → 声纹标签（如 "turn_1" → "会议人1"） */
    private val turnSpeakerMap = mutableMapOf<String, String>()

    /** 轮次 → 起止时间 (startMs, endMs)，供 handleVolcengineSentence 查找归属 */
    private val turnTimeRanges = mutableMapOf<String, Pair<Long, Long>>()

    /** 声纹结果未就绪时的句子暂存（轮次 → 句子列表） */
    private data class PendingSentence(
        val text: String, val startMs: Long, val endMs: Long,
        val sentenceId: Long, val isContinuation: Boolean
    )
    private val pendingSentences = mutableMapOf<String, MutableList<PendingSentence>>()


    // ── 音频管线扇出通道：WAV 存档与转写链各自独立背压 ──
    // 不能共用一条队列：加密 WAV 写入的 IO 抖动会拖慢 VAD/ASR 链，反之 final
    // decode 卡顿也会丢录音存档。DROP_OLDEST 保证最坏情况只丢最旧帧不阻塞采集
    private val wavChannel = kotlinx.coroutines.channels.Channel<ByteArray>(
        capacity = 256, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST) // ≈25.6s
    private val procChannel = kotlinx.coroutines.channels.Channel<ByteArray>(
        capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)  // ≈6.4s

    init {
        // --- 音频采集 → 零计算分发器 → 两条独立消费链 ---
        // 原先单收集器串行做 加密WAV写+VAD+声纹+ASR，任一环节 >100ms 就积压
        // 6.4s 共享缓冲后四路一起丢帧（连录音存档都缺）
        viewModelScope.launch(Dispatchers.IO) {
            audioCaptureManager.audioStream.collect { pcmData ->
                if (_uiState.value.isMeetingActive && !_uiState.value.isPaused) {
                    wavChannel.trySend(pcmData)
                    procChannel.trySend(pcmData)
                }
            }
        }

        // 分支 1：WAV 录音存档（加密文件 IO，独立于转写链）
        viewModelScope.launch(Dispatchers.IO) {
            for (pcmData in wavChannel) {
                try {
                    wavRecorder?.write(pcmData)
                } catch (e: Exception) {
                    android.util.Log.e("MeetingViewModel", "WAV 写入异常: ${e.message}")
                }
            }
        }

        // 分支 2：VAD → 声纹 feed → 切轮 → ASR feed。
        // 同帧内保持原有顺序语义：切轮与 ASR 喂入的相对时序决定说话人边界归属，
        // 不可再拆并行（会引入 ±几帧的归属漂移）
        viewModelScope.launch(Dispatchers.IO) {
            for (pcmData in procChannel) {
                processFrame(pcmData)
            }
        }

        // ── TranscriptionUseCase 状态收集 ──
        viewModelScope.launch {
            transcriptionUseCase.interimText.collect { text ->
                _uiState.update { it.copy(interimText = text) }
            }
        }

        viewModelScope.launch {
            transcriptionUseCase.sentenceFlow.collect { s ->
                val engineType = transcriptionUseCase.currentEngine.value
                if (engineType == com.example.meetingtranscriber.engine.AsrEngineType.VOLCENGINE_CLOUD) {
                    // 豆包：服务端 speaker_info 不可靠，走本地声纹
                    handleVolcengineSentence(s)
                } else {
                    // 通义听悟/其他：服务端 speaker_id 直接使用
                    appendSegment(s.speakerId, s.text, s.startTimeMs, s.endTimeMs,
                        s.sentenceId, s.isContinuation)
                }
            }
        }

        viewModelScope.launch {
            transcriptionUseCase.engineStatus.collect { status ->
                val connState = when (status.state) {
                    EngineState.RUNNING -> ConnectionState.CONNECTED
                    EngineState.READY -> ConnectionState.CONNECTED
                    EngineState.LOADING -> ConnectionState.CONNECTING
                    EngineState.ERROR -> ConnectionState.FAILED
                    EngineState.IDLE -> ConnectionState.DISCONNECTED
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

    /**
     * 转写链单帧处理（分支 2 消费协程串行调用，100ms/帧）：
     * VAD（UI 指示 + 声纹切轮）→ 声纹 feed → ASR 喂入。
     *
     * - 通义听悟: 服务端 diarization，声纹不介入
     * - 豆包/Volcengine: 本地 CAM++ 声纹，VAD 边沿切轮
     */
    private fun processFrame(pcmData: ByteArray) {
        // 1. 能量 VAD — UI 指示 + 声纹切轮边沿
        val isVoice = vadDetector.isVoice(pcmData)
        if (_uiState.value.isSpeaking != isVoice) {
            _uiState.update { it.copy(isSpeaking = isVoice) }
        }

        // 2. 声纹切轮 + feed（仅豆包模式下启用）
        val vp = voiceprintIdentifier
        if (vp != null && vp.isEnabled) {
            // 静音 → 语音：新轮次开始
            if (!wasVoice && isVoice) {
                turnCounter++
                currentTurnId = "turn_$turnCounter"
                currentTurnStartMs = frameCount * FRAME_DURATION_MS
                android.util.Log.d("MeetingViewModel",
                    "声纹轮次开始: $currentTurnId @${currentTurnStartMs}ms")
            }
            // 语音持续：累积声纹样本
            if (isVoice) {
                vp.feed(pcmData)
            }
            // 语音 → 静音：轮次结束，触发异步声纹识别
            if (wasVoice && !isVoice) {
                // 记录本轮的结束时间戳
                val endMs = frameCount * FRAME_DURATION_MS
                synchronized(voiceprintLock) {
                    turnTimeRanges[currentTurnId] = (currentTurnStartMs to endMs)
                }
                vp.onTurnEnded(currentTurnId)
                android.util.Log.d("MeetingViewModel",
                    "声纹轮次结束: $currentTurnId (${currentTurnStartMs}-${endMs}ms)")
            }
            wasVoice = isVoice
        }

        // 3. ASR 无条件喂入（服务端自行 VAD）
        try {
            transcriptionUseCase.processAudio(pcmData)
        } catch (e: Exception) {
            android.util.Log.e("MeetingViewModel",
                "processAudio 异常: ${e.message}")
        }
        frameCount++
    }

    /** 在线/离线会议 — 引擎自动路由 */
    fun startMeeting(
        title: String = "会议_${System.currentTimeMillis()}",
        language: String = "cn",
        engineTypeOverride: com.example.meetingtranscriber.engine.AsrEngineType? = null
    ) {
        // 防止快速双击导致重复启动
        if (_uiState.value.isMeetingActive || isStartingMeeting) {
            android.util.Log.w("MeetingViewModel", "会议已在进行中或正在启动，忽略重复 startMeeting")
            return
        }
        isStartingMeeting = true
        viewModelScope.launch {
            // 本次启动已创建的会议行 id（失败回滚用；不能用 currentMeetingId 判断——
            // 它可能残留上一场会议的 id）
            var createdMeetingId = 0L
            try {
                // 清空上次会议的残留数据
                _segments.value = emptyList()
                lastAppendedSentenceId = -1L
                speakerLabelMap.clear()
                pendingSegmentsForRecovery.clear()

                // 重置声纹状态
                voiceprintIdentifier?.releaseModel()
                voiceprintIdentifier = null
                frameCount = 0L
                wasVoice = false
                turnCounter = 0
                currentTurnId = ""
                currentTurnStartMs = 0L
                synchronized(voiceprintLock) {
                    turnSpeakerMap.clear()
                    turnTimeRanges.clear()
                    pendingSentences.clear()
                }

                if (!audioCaptureManager.hasPermission()) {
                    _uiState.update { it.copy(errorMessage = "缺少录音权限") }
                    abortStartup(createdMeetingId)
                    return@launch
                }

                currentMeetingTitle = title.ifBlank { "会议" }
                currentMeetingId = meetingRepository.createMeeting(title)
                createdMeetingId = currentMeetingId
                meetingStartTime = System.currentTimeMillis()

                // AudioRecord 构建 + startRecording 含系统服务 IPC，移出主线程
                val started = withContext(Dispatchers.IO) { audioCaptureManager.start() }
                if (!started) {
                    _uiState.update { it.copy(errorMessage = "启动音频采集失败") }
                    abortStartup(createdMeetingId)
                    return@launch
                }

                // 同时启动本地 WAV 录音存档
                val app = getApplication<com.example.meetingtranscriber.MeetingApplication>()
                val recordingsDir = app.getExternalFilesDir("realtime_recordings") ?: app.filesDir
                recordingsDir.mkdirs()
                val wavFile = File(recordingsDir, "realtime_${currentMeetingId}_${System.currentTimeMillis()}.wav")
                // Keystore 取密钥 + EncryptedFile 建流是慢操作（低端机数百 ms），移出主线程
                val recorder = withContext(Dispatchers.IO) {
                    val encKey = CryptoManager.getFileSecretKey()
                    WavRecorder(wavFile, encKey).takeIf { it.start() }
                }
                wavRecorder = recorder
                if (recorder == null) {
                    // 录音存档是会议记录完整性的硬要求：启动失败直接中止并明确报错，
                    // 不再静默继续（那会产生"有转写没录音"的残缺记录）
                    _uiState.update { it.copy(errorMessage = "录音存档创建失败（检查存储空间后重试）") }
                    abortStartup(createdMeetingId)
                    return@launch
                }
                meetingRepository.updateAudioFilePath(currentMeetingId, wavFile.absolutePath)

                if (!connectAsr(language, engineTypeOverride)) {
                    abortStartup(createdMeetingId)
                    return@launch
                }

                // 豆包模式下初始化本地声纹识别
                val engineType = transcriptionUseCase.currentEngine.value
                if (engineType == com.example.meetingtranscriber.engine.AsrEngineType.VOLCENGINE_CLOUD) {
                    val vp = VoiceprintIdentifier(viewModelScope)
                    vp.onIdentified = { turnId, label ->
                        onSpeakerIdentified(turnId, label)
                    }
                    if (vp.initialize(getApplication())) {
                        voiceprintIdentifier = vp
                        android.util.Log.i("MeetingViewModel", "声纹识别已启用 (CAM++)")
                    } else {
                        android.util.Log.w("MeetingViewModel", "声纹模型加载失败，豆包模式下说话人标签将不可用")
                        voiceprintIdentifier = null
                    }
                }

                _uiState.update { it.copy(
                    isMeetingActive = true,
                    isPaused = false,
                    isSpeaking = false,
                    errorMessage = null,
                    selectedLanguage = language
                ) }
                startTimer()
                startRecoverySaver()
                isStartingMeeting = false
            } catch (e: Exception) {
                android.util.Log.e("MeetingViewModel", "startMeeting 异常: ${e.message}", e)
                _uiState.update { it.copy(errorMessage = "启动会议失败: ${e.message}") }
                abortStartup(createdMeetingId)
            }
        }
    }

    /**
     * 启动失败统一回滚：停采集/录音存档/前台服务（Fragment 在 startMeeting 之前
     * 已拉起服务，任何失败出口不清理都会留下热麦克风 + 常驻通知），并删除本次
     * 已创建的空会议行。保证失败后可干净重试。
     */
    private suspend fun abortStartup(createdMeetingId: Long) {
        isStartingMeeting = false
        voiceprintIdentifier?.releaseModel()
        voiceprintIdentifier = null
        try { audioCaptureManager.stop() } catch (_: Exception) {}
        try { wavRecorder?.cancel() } catch (_: Exception) {}
        wavRecorder = null
        val app = getApplication<MeetingApplication>()
        try {
            app.stopService(android.content.Intent(
                app, com.example.meetingtranscriber.audio.AudioCaptureService::class.java))
        } catch (_: Exception) {}
        if (createdMeetingId != 0L) {
            try { meetingRepository.deleteMeeting(createdMeetingId) } catch (_: Exception) {}
            currentMeetingId = 0L
        }
    }

    /** 使用 TranscriptionUseCase → EngineRouter 智能路由启动 ASR */
    private var asrConnecting = false

    private suspend fun connectAsr(
        language: String = "cn",
        engineTypeOverride: com.example.meetingtranscriber.engine.AsrEngineType? = null
    ): Boolean {
        // 防止重复连接
        if (asrConnecting || transcriptionUseCase.isRunning.value) {
            android.util.Log.w("MeetingViewModel", "ASR 已在运行或连接中，跳过重复调用")
            return false
        }
        asrConnecting = true
        try {
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
        vadDetector.reset()
        return true
        } finally {
            asrConnecting = false
        }
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
            // 先等残余帧被双通道消费完（WAV 写盘 + ASR 喂入），再关录音文件——
            // 通道消费是异步的，先 stop() 会把仍在 wavChannel 里的尾音丢掉
            delay(150)
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

            // 清空转写列表，避免二次进入时残留旧数据
            _segments.value = emptyList()
            speakerLabelMap.clear()

            // 释放声纹模型（26MB ONNX，会后腾出内存）
            voiceprintIdentifier?.releaseModel()
            voiceprintIdentifier = null
            synchronized(voiceprintLock) {
                turnSpeakerMap.clear()
                turnTimeRanges.clear()
                pendingSentences.clear()
            }

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

    /** 关闭纪要审核弹窗 */
    fun dismissSummaryReview() {
        _uiState.update { it.copy(showSummaryReviewDialog = false) }
    }

    /** 保存用户在弹窗中编辑的纪要 */
    fun saveSummaryForReview(text: String) {
        val id = _uiState.value.summaryMeetingId
        if (id == 0L || text.isBlank()) return
        viewModelScope.launch {
            meetingRepository.saveSummary(id, text)
            _uiState.update { it.copy(latestSummary = text) }
        }
    }

    /** 获取 segments 供外部（弹窗导出）使用 */
    fun getSegmentsForSummary(): Long {
        return _uiState.value.summaryMeetingId
    }

    /** 导出纪要文本到文件并触发系统分享 */
    fun exportSummaryToFile(context: android.content.Context, summary: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<MeetingApplication>()
                val dir = java.io.File(app.getExternalFilesDir(null), "exports")
                dir.mkdirs()
                val dateStr = java.text.SimpleDateFormat(
                    "yyyy-MM-dd_HHmm", java.util.Locale.getDefault()
                ).format(java.util.Date())
                val file = java.io.File(dir, "纪要_${dateStr}.txt")
                file.writeText(summary, Charsets.UTF_8)

                withContext(Dispatchers.Main) {
                    com.example.meetingtranscriber.ui.export.ExportHelper.shareFile(
                        context, file, "text/plain"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("MeetingViewModel", "纪要导出失败: ${e.message}")
            }
        }
    }

    /** 重新生成摘要（从审核弹窗触发，会直接更新弹窗中的文本） */
    fun regenerateSummaryForReview() {
        val id = _uiState.value.summaryMeetingId
        if (id == 0L || _uiState.value.isGeneratingSummary) return
        viewModelScope.launch {
            var progressJob: Job? = null
            try {
                val segments = transcriptRepository.getSegmentsOnce(id)
                if (segments.isEmpty()) {
                    _uiState.update { it.copy(isGeneratingSummary = false, summaryProgress = 0f) }
                    return@launch
                }
                _uiState.update { it.copy(isGeneratingSummary = true, summaryProgress = 0f) }

                progressJob = launch {
                    summaryUseCase.generationProgress.collect { progress ->
                        _uiState.update { it.copy(summaryProgress = progress) }
                    }
                }

                val fullText = segments.joinToString("\n") {
                    "${it.displaySpeaker} [${it.formattedTime}]: ${it.text}"
                }
                val summary = summaryUseCase.generate(
                    getApplication(), fullText, com.example.meetingtranscriber.engine.SummaryStyle.STANDARD
                ).getOrElse {
                    MeetingSummaryGenerator.generate(fullText)
                }
                meetingRepository.saveSummary(id, summary)
                _uiState.update { it.copy(
                    isGeneratingSummary = false,
                    summaryProgress = 1f,
                    latestSummary = summary,
                    showSummaryReviewDialog = true
                ) }
            } catch (e: Exception) {
                android.util.Log.w("MeetingViewModel", "重新生成纪要失败: ${e.message}")
                _uiState.update { it.copy(
                    isGeneratingSummary = false,
                    summaryProgress = 0f,
                    showSummaryReviewDialog = true,
                    errorMessage = "重新生成失败: ${e.message}"
                ) }
            } finally {
                progressJob?.cancel()
                try { summaryUseCase.dispose() } catch (_: Exception) {}
            }
        }
    }

    /** 说话人标签映射 persist（speakerId → "会议人N"），用于 UI speakerLabels 和 speakerCount */

    /** 语气词与标点（段落只含这些字符则视为静音幻觉，不入库） */
    private val fillerChars = Regex("[嗯啊呃哦哎唉喔噢呀嘛哈哼呵\\s，。,.!！?？、~…]")

    /**
     * 通义听悟路径：用服务端 speakerId 解析为 displaySpeaker，再委托
     * [appendSegmentWithSpeaker] 完成文本处理+合并+入库。
     */
    private fun appendSegment(
        speakerId: String, text: String, startMs: Long, endMs: Long,
        sentenceId: Long, isContinuation: Boolean = false
    ) {
        val label = speakerLabelMap.getOrPut(speakerId) {
            "会议人${speakerLabelMap.size + 1}"
        }
        // speakerId 作为 TranscriptSegment.speakerId（原始标识），displaySpeaker 用 label
        appendSegmentWithSpeaker(label, text, startMs, endMs, sentenceId, isContinuation,
            rawSpeakerId = speakerId)
    }

    private fun uiStateHasSpeaker(speakerId: String): Boolean =
        _uiState.value.speakerLabels.containsKey(speakerId)

    // ═══════════════════════════════════════════════════════════
    // 声纹路由（豆包/Volcengine 模式下替代服务端 speakerId）
    // ═══════════════════════════════════════════════════════════

    /**
     * 豆包模式下 ASR 句子到达：按时间查归属轮次，声纹就绪则直接追加，
     * 否则暂存到 [pendingSentences] 等待 [onSpeakerIdentified] flush。
     */
    private fun handleVolcengineSentence(s: AsrSentence) {
        val turnId = findTurnForTime(s.startTimeMs)
        if (turnId == null) {
            val fallbackLabel = synchronized(voiceprintLock) {
                speakerLabelMap.getOrPut("0") { "会议人${speakerLabelMap.size + 1}" }
            }
            appendSegmentWithSpeaker(fallbackLabel, s.text, s.startTimeMs, s.endTimeMs,
                s.sentenceId, s.isContinuation)
            return
        }

        val label = synchronized(voiceprintLock) { turnSpeakerMap[turnId] }
        if (label != null) {
            // 声纹已就绪 → 直接落句
            appendSegmentWithSpeaker(label, s.text, s.startTimeMs, s.endTimeMs,
                s.sentenceId, s.isContinuation)
        } else {
            // 声纹还在跑 → 缓冲
            synchronized(voiceprintLock) {
                pendingSentences.getOrPut(turnId) { mutableListOf() }
                    .add(PendingSentence(s.text, s.startTimeMs, s.endTimeMs,
                        s.sentenceId, s.isContinuation))
            }
        }
    }

    /**
     * 声纹识别回调：将 VAD 轮次映射为持久化说话人标签，并 flush 该轮次缓冲句子。
     *
     * 在线程"voiceprint"上回调（见 [VoiceprintIdentifier.embeddingDispatcher]），
     * 更新 speakerLabelMap / turnSpeakerMap / turnTimeRanges 后通过 ViewModelScope
     * 在主线程安全追加 UI 段落。
     */
    private fun onSpeakerIdentified(turnId: String, label: String?) {
        // 快照：在锁内完成 map 更新 + 取走缓冲句子，锁外再做 UI/DB 操作
        val effectiveLabel: String
        val toFlush: List<PendingSentence>
        val labelSnapshot: Map<String, String>
        synchronized(voiceprintLock) {
            if (label == null) {
                effectiveLabel = "会议人${speakerLabelMap.size + 1}"
                turnSpeakerMap[turnId] = effectiveLabel
            } else {
                if (!speakerLabelMap.containsValue(label)) {
                    speakerLabelMap[label] = label
                }
                turnSpeakerMap[turnId] = label
                effectiveLabel = label
            }
            toFlush = pendingSentences.remove(turnId)?.toList() ?: emptyList()
            labelSnapshot = speakerLabelMap.toMap()
        }

        // UI 更新 + flush（lock 外，避免阻塞 voiceprint 线程）
        _uiState.update { it.copy(
            speakerLabels = labelSnapshot,
            speakerCount = labelSnapshot.values.toSet().size
        ) }

        toFlush.forEach { ps ->
            appendSegmentWithSpeaker(effectiveLabel, ps.text, ps.startMs, ps.endMs,
                ps.sentenceId, ps.isContinuation)
        }
    }

    /**
     * 按句子起始时间查找所属 VAD 轮次。
     *
     * 策略：遍历 [turnTimeRanges]，找 startMs ≤ sentenceStartMs 且 endMs ≥ sentenceStartMs
     * 的轮次。若多发命中（时间窗重叠），取 startMs 最大者（最近一轮）。
     * 若无完全覆盖的 → 找 startMs ≤ sentenceStartMs 中最晚的一轮（句子可能落在轮次结束后）。
     */
    private fun findTurnForTime(sentenceStartMs: Long): String? {
        // 快照 turnTimeRanges（IO 线程并发写入），避免遍历中 ConcurrentModificationException
        val ranges = synchronized(voiceprintLock) { turnTimeRanges.toMap() }
        if (ranges.isEmpty()) return null

        // 精确命中：句子开始时间在某个轮次时间窗内
        val exact = ranges.entries
            .filter { (_, range) -> sentenceStartMs in range.first..range.second }
            .maxByOrNull { (_, range) -> range.first }
        if (exact != null) return exact.key

        // 宽松匹配：句子在某个轮次结束后不久到达（ASR 延迟）
        // 取 startMs ≤ sentenceStartMs 且 gap 在 5000ms 以内的最近轮次
        val close = ranges.entries
            .filter { (_, range) -> range.first <= sentenceStartMs }
            .minByOrNull { (_, range) -> sentenceStartMs - range.first }
        if (close != null && (sentenceStartMs - close.value.first) <= 5000L) {
            return close.key
        }

        // 最后兜底：返回距离最近的轮次（限制在 10000ms 以内，超出视为不匹配）
        val nearest = ranges.entries
            .minByOrNull { (_, range) -> kotlin.math.abs(sentenceStartMs - range.first) }
        if (nearest != null && kotlin.math.abs(sentenceStartMs - nearest.value.first) <= 10000L) {
            return nearest.key
        }
        return null
    }

    /**
     * 用指定 speaker 标签追加句子。通义听悟路径由 [appendSegment] 解析 label 后
     * 委托至此；豆包/声纹路径直接调用，speaker 即为声纹返回标签。
     *
     * @param speaker        显示标签（如 "会议人1"）
     * @param rawSpeakerId   TranscriptSegment.speakerId 原始标识，为 null 时复用 speaker
     */
    private fun appendSegmentWithSpeaker(
        speaker: String, text: String, startMs: Long, endMs: Long,
        sentenceId: Long, isContinuation: Boolean,
        rawSpeakerId: String? = null
    ) {
        val formattedText = TextFormatter.spokenNumberToDigits(TextFormatter.format(text))
        if (formattedText.replace(fillerChars, "").isBlank()) return

        val segSpeakerId = rawSpeakerId ?: speaker

        val last = _segments.value.lastOrNull()
        if (isContinuation && last != null && last.speakerId == segSpeakerId &&
            lastAppendedSentenceId == sentenceId - 1
        ) {
            val merged = last.copy(text = last.text + formattedText, endTimeMs = endMs)
            _segments.update { it.dropLast(1) + merged }
            _uiState.update { it.copy(interimText = "") }
            lastAppendedSentenceId = sentenceId
            viewModelScope.launch {
                transcriptRepository.mergeContinuation(
                    currentMeetingId, merged.sentenceId, merged.text, merged.endTimeMs)
            }
            return
        }

        // 兜底：如果 speaker 标签未在 speakerLabelMap 中注册，补充注册
        // 注：voiceprint 线程和主线程并发访问，加锁保护
        synchronized(voiceprintLock) {
            if (!speakerLabelMap.containsValue(speaker)) {
                speakerLabelMap[speaker] = speaker
            }
        }

        val segment = TranscriptSegment(
            meetingId = currentMeetingId,
            speakerId = segSpeakerId,
            displaySpeaker = speaker,
            text = formattedText,
            startTimeMs = startMs,
            endTimeMs = endMs,
            sentenceId = sentenceId
        )
        _segments.update { it + segment }
        lastAppendedSentenceId = sentenceId
        pendingSegmentsForRecovery.add(segment)
        if (!uiStateHasSpeaker(speaker)) {
            _uiState.update { it.copy(
                interimText = "",
                speakerLabels = speakerLabelMap.toMap(),
                speakerCount = speakerLabelMap.values.toSet().size
            ) }
        } else {
            _uiState.update { it.copy(interimText = "") }
        }
        viewModelScope.launch {
            transcriptRepository.saveSegment(segment)
        }
    }

    private fun generateSummary(meeting: MeetingInfo) {
        viewModelScope.launch {
            var progressJob: Job? = null
            try {
                val segments = transcriptRepository.getSegmentsOnce(meeting.id)
                // 无有效转写（如全部为静音幻觉被过滤）→ 不生成纪要，避免 LLM 对空文本编造内容
                if (segments.isEmpty()) {
                    android.util.Log.i("MeetingViewModel", "无转写内容，跳过纪要生成")
                    return@launch
                }

                _uiState.update { it.copy(isGeneratingSummary = true, summaryProgress = 0f) }

                // 收集摘要进度
                progressJob = launch {
                    summaryUseCase.generationProgress.collect { progress ->
                        _uiState.update { it.copy(summaryProgress = progress) }
                    }
                }

                val fullText = segments.joinToString("\n") {
                    "${it.displaySpeaker} [${it.formattedTime}]: ${it.text}"
                }
                // 优先使用 SummaryUseCase（通过引擎路由），回退到 deprecated 规则生成器
                val summary = summaryUseCase.generate(
                    getApplication(), fullText, com.example.meetingtranscriber.engine.SummaryStyle.STANDARD
                ).getOrElse {
                    // Fallback: 所有云端 LLM 不可用时，回退到内置规则生成器
                    MeetingSummaryGenerator.generate(fullText)
                }
                meetingRepository.saveSummary(meeting.id, summary)

                // 导出 TXT 纪要文件（长会议全文可达数百 KB，文件写移出主线程）
                withContext(Dispatchers.IO) {
                    saveSummaryToFile(meeting, segments, summary)
                }

                _uiState.update { it.copy(
                    isGeneratingSummary = false,
                    summaryProgress = 1f,
                    showSummaryReviewDialog = true,
                    latestSummary = summary,
                    summaryMeetingId = meeting.id
                ) }
            } catch (e: Exception) {
                android.util.Log.w("MeetingViewModel", "纪要生成失败: ${e.message}")
                _uiState.update { it.copy(isGeneratingSummary = false, summaryProgress = 0f) }
            } finally {
                // 进度收集协程在 finally 统一取消（原先异常路径不取消 → 协程泄漏）
                progressJob?.cancel()
                // 云端引擎 dispose 是轻量清理，释放连接和缓存。
                try {
                    summaryUseCase.dispose()
                } catch (e: Exception) {
                    android.util.Log.w("MeetingViewModel", "summaryUseCase.dispose 失败: ${e.message}")
                }
            }
        }
    }

    /** 将会议纪要导出为 TXT 文件到 summaries/ 目录 */
    private fun saveSummaryToFile(
        meeting: MeetingInfo,
        segments: List<TranscriptSegment>,
        summary: String
    ) {
        try {
            val app = getApplication<MeetingApplication>()
            val dir = java.io.File(app.getExternalFilesDir(null), "summaries")
            dir.mkdirs()
            val dateStr = java.text.SimpleDateFormat(
                "yyyy-MM-dd_HHmm", java.util.Locale.getDefault()
            ).format(java.util.Date(meeting.startTime))
            val safeTitle = meeting.title.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val file = java.io.File(dir, "${safeTitle}_${dateStr}_纪要.txt")
            val content = buildString {
                appendLine("=".repeat(50))
                appendLine("会议纪要")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("标题: ${meeting.title}")
                appendLine("时间: ${meeting.formattedStartTime}")
                appendLine("时长: ${meeting.formattedDuration}")
                appendLine("发言人: ${meeting.speakerCount} 人")
                appendLine("转写条数: ${meeting.segmentCount} 条")
                appendLine("引擎: ASR=${meeting.asrEngineType ?: "-"} | LLM=${meeting.llmEngineType ?: "-"}")
                appendLine()
                appendLine("-".repeat(50))
                appendLine("转写内容")
                appendLine("-".repeat(50))
                appendLine()
                segments.forEach { seg ->
                    appendLine("${seg.displaySpeaker} [${seg.formattedTime}]")
                    appendLine(seg.text)
                    appendLine()
                }
                appendLine("-".repeat(50))
                appendLine("AI 摘要")
                appendLine("-".repeat(50))
                appendLine()
                appendLine(summary)
            }
            file.writeText(content, Charsets.UTF_8)
            android.util.Log.i("MeetingViewModel", "纪要已导出: ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.w("MeetingViewModel", "纪要文件导出失败: ${e.message}")
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
            // stop() 而非 cancel()：cancel 会删除录音文件——会议进行中 ViewModel
            // 被销毁（进程回收等）时必须保留已录内容，配合恢复机制续会
            wavRecorder?.stop()
            wavRecorder = null
            voiceprintIdentifier?.releaseModel()
            voiceprintIdentifier = null
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
            val isOffline = transcriptionUseCase.currentEngine.value?.isCloud == false
            // 调用方线程只做集合快照，序列化 + 落库移到后台（每 5s 一次，避免周期掉帧）
            val labelSnapshot = speakerLabelMap.toMap()
            val pendingSnapshot = pendingSegmentsForRecovery.toList()
            withContext(Dispatchers.Default) {
                val state = RecoveryStateEntity(
                    meetingId = currentMeetingId,
                    title = currentMeetingTitle,
                    startTime = meetingStartTime,
                    isOfflineMode = isOffline,
                    isDemoMode = false,
                    currentTaskId = "",  // TranscriptionUseCase 已封装引擎细节
                    audioBufferFilePath = null,
                    audioBufferFrameCount = 0,
                    speakerLabelMapJson = gson.toJson(labelSnapshot),
                    pendingSegmentsJson = gson.toJson(pendingSnapshot)
                )
                recoveryStateDao.upsert(state)
            }
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

            // 重置声纹追踪状态（崩溃前内存数据已丢失，从零开始）
            frameCount = 0L
            wasVoice = false
            turnCounter = 0
            currentTurnId = ""
            currentTurnStartMs = 0L
            synchronized(voiceprintLock) {
                turnSpeakerMap.clear()
                turnTimeRanges.clear()
                pendingSentences.clear()
            }

            val listType = object : TypeToken<List<TranscriptSegment>>() {}.type
            val pendingSegments: List<TranscriptSegment> = Gson().fromJson(state.pendingSegmentsJson, listType)
            pendingSegmentsForRecovery.clear()

            _uiState.update { it.copy(
                isMeetingActive = true,
                isPaused = false,
                speakerLabels = speakerLabelMap.toMap(),
                speakerCount = speakerLabelMap.values.toSet().size
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

            // 豆包模式下重新初始化本地声纹识别
            val engineType = transcriptionUseCase.currentEngine.value
            if (engineType == com.example.meetingtranscriber.engine.AsrEngineType.VOLCENGINE_CLOUD) {
                val vp = VoiceprintIdentifier(viewModelScope)
                vp.onIdentified = { turnId, label ->
                    onSpeakerIdentified(turnId, label)
                }
                if (vp.initialize(getApplication())) {
                    voiceprintIdentifier = vp
                    android.util.Log.i("MeetingViewModel", "声纹识别已恢复 (CAM++)")
                } else {
                    voiceprintIdentifier = null
                }
            }

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

    companion object {
        /** 音频帧时长（ms），与 AudioCaptureManager 100ms/帧一致 */
        private const val FRAME_DURATION_MS = 100L
    }
}
