package com.example.meetingtranscriber.ui.meeting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.audio.AudioCaptureService
import com.example.meetingtranscriber.data.db.RecoveryStateEntity
import com.example.meetingtranscriber.databinding.FragmentMeetingBinding
import com.example.meetingtranscriber.network.ConnectionState
import com.example.meetingtranscriber.util.SUPPORTED_LANGUAGES
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MeetingFragment : Fragment() {

    private enum class StartMode { ONLINE, OFFLINE }

    private var _binding: FragmentMeetingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MeetingViewModel by viewModels()
    private lateinit var adapter: TranscriptAdapter
    private var pendingStartMode: StartMode = StartMode.ONLINE

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startMeetingWithService(pendingStartMode)
        else Toast.makeText(requireContext(), "录音权限是必需的", Toast.LENGTH_LONG).show()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        checkAudioPermissionAndStart(pendingStartMode)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMeetingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSpinner()
        setupRecyclerView()
        setupClickListeners()
        observeState()
    }

    private fun setupSpinner() {
        val languageAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            SUPPORTED_LANGUAGES.map { it.displayName }
        )
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = languageAdapter

        val tagAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf("无标签", "产品", "技术", "客户", "人事", "其他")
        )
        tagAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTag.adapter = tagAdapter
    }

    private fun getSelectedLanguage(): String {
        val pos = binding.spinnerLanguage.selectedItemPosition
        return SUPPORTED_LANGUAGES.getOrElse(pos) { SUPPORTED_LANGUAGES[0] }.code
    }

    private fun getSelectedTag(): String? {
        val pos = binding.spinnerTag.selectedItemPosition
        val tags = listOf(null, "产品", "技术", "客户", "人事", "其他")
        return tags.getOrElse(pos) { null }
    }

    private fun setupRecyclerView() {
        adapter = TranscriptAdapter()
        binding.rvTranscript.adapter = adapter
        binding.rvTranscript.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
    }

    private fun setupClickListeners() {
        binding.etMeetingTitle.setText(
            "会议_${java.text.SimpleDateFormat("MM-dd_HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        )

        binding.btnOnline.setOnClickListener { startOnlineMeeting() }
        binding.btnOffline.setOnClickListener { startOfflineMeeting() }

        binding.btnStartEnd.setOnClickListener {
            if (viewModel.uiState.value.isMeetingActive) {
                viewModel.endMeeting()
                stopMeetingService()
            } else {
                startOnlineMeeting()
            }
        }

        binding.btnPause.setOnClickListener { viewModel.togglePause() }
        binding.btnRetry.setOnClickListener { viewModel.retryConnection() }
    }

    fun startOnlineMeeting() { requestPermissionsAndStart(StartMode.ONLINE) }
    fun startOfflineMeeting() { requestPermissionsAndStart(StartMode.OFFLINE) }

    fun recoverFromCrash(state: RecoveryStateEntity) {
        viewModel.recoverFromCrash(state)
    }

    private fun requestPermissionsAndStart(mode: StartMode) {
        pendingStartMode = mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotification = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotification) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkAudioPermissionAndStart(mode)
    }

    private fun checkAudioPermissionAndStart(mode: StartMode) {
        val hasAudio = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudio) startMeetingWithService(mode)
        else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startMeetingWithService(mode: StartMode) {
        try {
            val intent = Intent(requireContext(), AudioCaptureService::class.java)
            ContextCompat.startForegroundService(requireContext(), intent)
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "无法启动前台服务：${e.message}", Toast.LENGTH_LONG).show()
            return
        } catch (e: IllegalStateException) {
            Toast.makeText(requireContext(), "应用在后台无法启动前台服务，请返回前台后重试", Toast.LENGTH_LONG).show()
            return
        }
        val title = binding.etMeetingTitle.text.toString().trim().ifBlank { "会议" }
        val language = getSelectedLanguage()
        val tag = getSelectedTag()
        when (mode) {
            StartMode.ONLINE -> viewModel.startMeeting(title, language, tag)
            StartMode.OFFLINE -> viewModel.startMeeting(
                title, language, tag,
                com.example.meetingtranscriber.engine.AsrEngineType.FUNASR_LOCAL
            )
        }
    }

    private fun stopMeetingService() {
        requireContext().stopService(Intent(requireContext(), AudioCaptureService::class.java))
    }

    private fun observeState() {
        // repeatOnLifecycle(RESUMED)：Tab 隐藏时停收（会议逻辑全在 ViewModel，
        // 录音/转写不受影响），切回时 StateFlow 重放最新状态恢复 UI
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                launch {
                    viewModel.segments.collectLatest { segments ->
                        adapter.submitSegments(segments) {
                            // 仅当已停在底部时跳到最新（无动画）：不打断往回翻看，
                            // 也避免每句 final 都触发平滑滚动动画
                            if (segments.isNotEmpty() && !binding.rvTranscript.canScrollVertically(1)) {
                                binding.rvTranscript.scrollToPosition(adapter.itemCount - 1)
                            }
                        }
                    }
                }

                // 拆三路收集：interim（~4次/s）、计时器（1次/s）、其余低频状态——
                // 任何一路发射不再全量刷 20+ 个 view（低端机持续掉帧源之一）
                launch {
                    viewModel.uiState.map { it.interimText }.distinctUntilChanged().collect { text ->
                        adapter.setInterimText(text.takeIf { it.isNotBlank() })
                    }
                }
                launch {
                    viewModel.uiState.map { it.elapsedSeconds }.distinctUntilChanged().collect { sec ->
                        binding.tvTimer.text =
                            "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
                    }
                }
                launch {
                    viewModel.uiState
                        .map { it.copy(interimText = "", elapsedSeconds = 0) }
                        .distinctUntilChanged()
                        .collect { updateUI(it) }
                }
            }
        }
    }

    private fun updateUI(state: MeetingUiState) {
        if (state.asrEngineName.isNotBlank()) {
            binding.tvEngineLabel.text = "当前引擎：${state.asrEngineName}"
            binding.tvEngineLabel.visibility = View.VISIBLE
        } else {
            binding.tvEngineLabel.visibility = View.GONE
        }

        if (state.isGeneratingSummary) {
            binding.progressSummary.visibility = View.VISIBLE
            binding.progressSummary.progress = (state.summaryProgress * 100).toInt()
        } else {
            binding.progressSummary.visibility = View.GONE
        }

        when (state.connectionState) {
            ConnectionState.CONNECTING -> {
                // 本地引擎首次加载模型需 3-4s：给出加载反馈，不再无提示假死
                binding.layoutConnectionBanner.visibility = View.VISIBLE
                binding.layoutConnectionBanner.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.status_paused)
                )
                binding.tvConnectionStatus.text = "正在启动语音引擎…"
                binding.btnRetry.visibility = View.GONE
            }
            ConnectionState.RECONNECTING -> {
                binding.layoutConnectionBanner.visibility = View.VISIBLE
                binding.layoutConnectionBanner.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.status_paused)
                )
                binding.tvConnectionStatus.text = "网络中断，正在重连..."
                binding.btnRetry.visibility = View.GONE
            }
            ConnectionState.FAILED -> {
                binding.layoutConnectionBanner.visibility = View.VISIBLE
                binding.layoutConnectionBanner.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.error_red)
                )
                // 优先显示引擎真实错误（本地模型加载失败时"检查网络"是误导）
                binding.tvConnectionStatus.text =
                    state.errorMessage ?: "连接失败，请检查网络后重试"
                binding.btnRetry.visibility = View.VISIBLE
            }
            else -> { binding.layoutConnectionBanner.visibility = View.GONE }
        }

        if (state.isMeetingActive) {
            binding.layoutTitleInput.visibility = View.GONE
            binding.btnStartEnd.text = "结束会议"
            binding.btnStartEnd.isEnabled = true
            binding.btnStartEnd.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.error_red)
            )
            binding.btnOnline.visibility = View.GONE
            binding.btnOffline.visibility = View.GONE
            binding.btnPause.visibility = View.VISIBLE
            binding.btnPause.text = if (state.isPaused) "继续" else "暂停"

            // 计时器由 observeState 的专属收集器更新，此处不再处理

            binding.ivStatus.background?.setTint(
                ContextCompat.getColor(
                    requireContext(),
                    when {
                        state.isPaused -> R.color.status_paused
                        state.isSpeaking -> R.color.status_recording
                        state.isConnected -> R.color.status_connected_silent
                        else -> R.color.interim_text
                    }
                )
            )
        } else {
            binding.layoutTitleInput.visibility = View.VISIBLE
            binding.btnStartEnd.text = "开始会议"
            binding.btnStartEnd.isEnabled = true
            binding.btnStartEnd.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.primary)
            )
            binding.btnOnline.visibility = View.VISIBLE
            binding.btnOffline.visibility = View.VISIBLE
            binding.btnPause.visibility = View.GONE
            binding.tvTimer.text = "00:00:00"
            binding.ivStatus.background?.setTint(
                ContextCompat.getColor(requireContext(), R.color.interim_text)
            )
        }

        if (state.errorMessage != null) {
            binding.tvError.visibility = View.VISIBLE
            binding.tvError.text = state.errorMessage
            binding.tvError.setOnClickListener { viewModel.clearError() }
        } else {
            binding.tvError.visibility = View.GONE
            binding.tvError.text = ""
            binding.tvError.setOnClickListener(null)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
