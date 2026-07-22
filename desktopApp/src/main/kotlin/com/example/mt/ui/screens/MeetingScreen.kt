package com.example.mt.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mt.config.EngineKeys
import com.example.mt.data.repository.MeetingRepository
import com.example.mt.data.repository.TranscriptRepository
import com.example.mt.domain.SummaryUseCase
import com.example.mt.domain.TranscriptionUseCase
import com.example.mt.engine.EngineRouter
import com.example.mt.engine.asr.TingwuEngine
import com.example.mt.engine.asr.VolcengineEngine
import com.example.mt.engine.llm.DashScopeEngine
import com.example.mt.engine.llm.DoubaoEngine
import com.example.mt.platform.*
import com.example.mt.audio.WavRecorder
import com.example.mt.ui.components.*
import com.example.mt.ui.meeting.MeetingUiState
import com.example.mt.ui.meeting.MeetingViewModel

@Composable
fun MeetingScreen(
    db: PlatformDatabase,
    kvStore: PlatformKeyValueStore,
    fileAccess: FileAccess,
) {
    // ── 构建依赖 ──
    val viewModel = remember {
        val meetingRepo = MeetingRepository(db.meetingQueries, db.recoveryQueries)
        val transcriptRepo = TranscriptRepository(db.transcriptQueries)

        val engineKeys = EngineKeys(
            tingwuAccessKeyId = kvStore.getString("tingwu_access_key_id"),
            tingwuAccessKeySecret = kvStore.getString("tingwu_access_key_secret"),
            tingwuAppKey = kvStore.getString("tingwu_app_key"),
            volcengineAsrApiKey = kvStore.getString("volcengine_asr_api_key"),
            volcengineAsrAccessToken = kvStore.getString("volcengine_asr_access_token"),
            arkApiKey = kvStore.getString("ark_api_key"),
            arkEndpointId = kvStore.getString("ark_endpoint_id"),
            dashScopeApiKey = kvStore.getString("dashscope_api_key"),
            preferredAsrEngine = try {
                com.example.mt.engine.AsrEngineType.valueOf(
                    kvStore.getString("preferred_asr_engine", "VOLCENGINE_CLOUD")
                )
            } catch (_: Exception) { com.example.mt.engine.AsrEngineType.VOLCENGINE_CLOUD },
            preferredLlmEngine = try {
                com.example.mt.engine.LlmEngineType.valueOf(
                    kvStore.getString("preferred_llm_engine", "DOUBAO_CLOUD")
                )
            } catch (_: Exception) { com.example.mt.engine.LlmEngineType.DOUBAO_CLOUD },
            autoFallback = kvStore.getBoolean("auto_fallback", true),
            summaryStyle = try {
                com.example.mt.engine.SummaryStyle.valueOf(
                    kvStore.getString("summary_style", "STANDARD")
                )
            } catch (_: Exception) { com.example.mt.engine.SummaryStyle.STANDARD },
            backgroundSilent = kvStore.getBoolean("background_silent", false),
            llmApiKeys = mapOf(
                com.example.mt.engine.LlmEngineType.DEEPSEEK_CLOUD to kvStore.getString("deepseek_api_key"),
                com.example.mt.engine.LlmEngineType.KIMI_CLOUD to kvStore.getString("kimi_api_key"),
                com.example.mt.engine.LlmEngineType.ZHIPU_CLOUD to kvStore.getString("zhipu_api_key"),
                com.example.mt.engine.LlmEngineType.SILICONFLOW_CLOUD to kvStore.getString("siliconflow_api_key"),
            ).filterValues { it.isNotBlank() },
        )

        val networkMonitor = NetworkMonitor()
        val engineRouter = EngineRouter(keys = engineKeys, networkMonitor = networkMonitor).also { router ->
            // 注入已配置密钥的引擎实例
            if (engineKeys.hasVolcengineKeys())
                router.setCloudEngines(volcengine = VolcengineEngine(engineKeys))
            if (engineKeys.hasTingwuKeys())
                router.setCloudEngines(tingwu = TingwuEngine(engineKeys))
            if (engineKeys.hasArkKey())
                router.setCloudEngines(doubao = DoubaoEngine(engineKeys))
            if (engineKeys.hasDashScopeKey())
                router.setCloudEngines(dashScope = DashScopeEngine(engineKeys))
        }

        val transcriptionUseCase = TranscriptionUseCase(engineRouter)
        val summaryUseCase = SummaryUseCase(engineRouter)

        MeetingViewModel(
            engineKeys = engineKeys,
            transcriptionUseCase = transcriptionUseCase,
            summaryUseCase = summaryUseCase,
            meetingRepo = meetingRepo,
            transcriptRepo = transcriptRepo,
            audioCapture = PlatformAudioCapture(),
            fileAccess = fileAccess,
            voiceprintLoader = VoiceprintModelLoader(),
            networkMonitor = networkMonitor,
            wavRecorder = WavRecorder(),
        )
    }

    // ── 收集状态 ──
    val uiState by viewModel.uiState.collectAsState()
    val segments by viewModel.transcriptSegments.collectAsState()

    // 自动滚到底部
    val listState = rememberLazyListState()
    LaunchedEffect(segments.size) {
        if (segments.isNotEmpty()) {
            listState.animateScrollToItem(segments.size - 1)
        }
    }

    // 声纹模型加载（IO 线程避免阻塞 UI）
    LaunchedEffect(Unit) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.loadVoiceprintModel()
        }
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose { viewModel.dispose() }
    }

    // ── UI ──
    Column(modifier = Modifier.fillMaxSize()) {
        // TopBar
        MeetingTopBar(
            uiState = uiState,
            onStart = { viewModel.startMeeting("新会议", uiState.selectedLanguage) },
            onPause = { viewModel.pauseMeeting() },
            onResume = { viewModel.resumeMeeting() },
            onStop = { viewModel.endMeeting() },
        )

        // 连接状态横幅
        ConnectionBanner(
            state = uiState.connectionState,
            engineName = uiState.asrEngineName,
        )

        // 错误信息
        uiState.errorMessage?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // 转录内容区
        Box(modifier = Modifier.weight(1f)) {
            if (!uiState.isMeetingActive && segments.isEmpty()) {
                // 空闲状态
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "点击下方按钮开始录制会议",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(segments, key = { it.id }) { segment ->
                        TranscriptItem(segment = segment)
                    }

                    // 实时临时文本
                    if (uiState.interimText.isNotBlank()) {
                        item(key = "interim") {
                            Text(
                                text = uiState.interimText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // 底部控制栏
        MeetingBottomBar(
            uiState = uiState,
            onLanguageSelected = { viewModel.switchLanguage(it) },
            onStart = { viewModel.startMeeting("新会议", uiState.selectedLanguage) },
            onPause = { viewModel.pauseMeeting() },
            onResume = { viewModel.resumeMeeting() },
            onStop = { viewModel.endMeeting() },
        )
    }

    // 纪要审核弹窗
    if (uiState.showSummaryReviewDialog) {
        SummaryReviewDialog(
            summary = uiState.latestSummary,
            meetingId = uiState.summaryMeetingId,
            onSave = { edited ->
                viewModel.saveEditedSummary(edited)
            },
            onDismiss = { viewModel.dismissSummaryDialog() },
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// TopBar
// ═══════════════════════════════════════════════════════════════

@Composable
private fun MeetingTopBar(
    uiState: MeetingUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(shadowElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 会议标题
            Text(
                text = if (uiState.isMeetingActive) "会议进行中" else "新会议",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )

            // 计时器
            if (uiState.isMeetingActive) {
                MeetingTimer(
                    elapsedSeconds = uiState.elapsedSeconds,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            // 控制按钮（顶部只保留停止）
            ControlButtons(
                isMeetingActive = uiState.isMeetingActive,
                isPaused = uiState.isPaused,
                onStart = onStart,
                onPause = onPause,
                onResume = onResume,
                onStop = onStop,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// BottomBar
// ═══════════════════════════════════════════════════════════════

@Composable
private fun MeetingBottomBar(
    uiState: MeetingUiState,
    onLanguageSelected: (String) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            // 状态行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatusIndicator(
                    isSpeaking = uiState.isSpeaking,
                    isPaused = uiState.isPaused,
                )

                Text(
                    text = "${uiState.speakerCount} 位发言人",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 控制行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 语言选择
                if (!uiState.isMeetingActive) {
                    LanguageSelector(
                        selectedCode = uiState.selectedLanguage,
                        onLanguageSelected = onLanguageSelected,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                }

                // 控制按钮
                ControlButtons(
                    isMeetingActive = uiState.isMeetingActive,
                    isPaused = uiState.isPaused,
                    onStart = onStart,
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop,
                )
            }
        }
    }
}
