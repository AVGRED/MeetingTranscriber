package com.example.meetingtranscriber.ui.meeting

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
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

    private enum class StartMode { ONLINE }

    private var _binding: FragmentMeetingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MeetingViewModel by viewModels()
    private lateinit var adapter: TranscriptAdapter
    private var pendingStartMode: StartMode = StartMode.ONLINE

    /** 上次 render 的 UI 状态：跳过无变化的 View set 操作，每帧省 15+ 次 view 属性写入 */
    private var lastState: MeetingUiState? = null

    /** 颜色缓存：ContextCompat.getColor() 含资源表 + 主题解析，isSpeaking 每 200ms
     *  翻转时白跑查找——跟 adapter 里一样的原理，电视固定亮色模式无需考虑 invalidate */
    private val colorCache = mutableMapOf<Int, Int>()
    private fun cachedColor(resId: Int): Int =
        colorCache.getOrPut(resId) { ContextCompat.getColor(requireContext(), resId) }

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
    }

    private fun getSelectedLanguage(): String {
        val pos = binding.spinnerLanguage.selectedItemPosition
        return SUPPORTED_LANGUAGES.getOrElse(pos) { SUPPORTED_LANGUAGES[0] }.code
    }

    private fun setupRecyclerView() {
        adapter = TranscriptAdapter()
        binding.rvTranscript.adapter = adapter
        binding.rvTranscript.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        // Fix B1: RV 高度由 ConstraintLayout 约束 (0dp)，内容变化不影响尺寸，
        // 设置后跳过每次 notify 的 measure pass
        binding.rvTranscript.setHasFixedSize(true)
        // Fix B2: item 简单（3 个 TextView），多缓存 6 个减少 inflate/bind 抖动
        binding.rvTranscript.setItemViewCacheSize(8)
        // Fix B3: 4K 软渲染设备上 300ms 插入/删除动画每帧全屏 draw，
        // 直播转写每分钟数十次 notify 动画叠加，关闭动画大幅减少 draw 开销
        binding.rvTranscript.itemAnimator = null
    }

    private fun setupClickListeners() {
        binding.etMeetingTitle.setText(
            "会议_${java.text.SimpleDateFormat("MM-dd_HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        )

        binding.btnOnline.setOnClickListener { startOnlineMeeting() }

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
        when (mode) {
            StartMode.ONLINE -> viewModel.startMeeting(title, language)
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
                        adapter.submitSegments(segments, isLive = true) {
                            // post 延迟到 RV 已完成 layout 后再读 scroll state，
                            // 避免在 RV 仍 dirty 时同步触发额外 layout pass
                            binding.rvTranscript.post {
                                if (segments.isNotEmpty() && !binding.rvTranscript.canScrollVertically(1)) {
                                    binding.rvTranscript.scrollToPosition(adapter.itemCount - 1)
                                }
                            }
                        }
                    }
                }

                // 拆三路收集：interim（~4次/s）、计时器（1次/s）、其余低频状态——
                // 任何一路发射不再全量刷 20+ 个 view（低端机持续掉帧源之一）
                launch {
                    viewModel.uiState.map { it.interimText }.distinctUntilChanged().collect { text ->
                        val hasText = text.isNotBlank()
                        binding.tvInterim.visibility = if (hasText) View.VISIBLE else View.GONE
                        if (hasText) binding.tvInterim.text = text
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
                // ── 纪要审核弹窗 ──
                launch {
                    viewModel.uiState
                        .map { it.showSummaryReviewDialog to it }
                        .distinctUntilChanged { old, new -> old.first == new.first &&
                            old.second.latestSummary == new.second.latestSummary }
                        .collect { (_, state) ->
                            if (state.showSummaryReviewDialog) {
                                showSummaryReviewDialog(state)
                            }
                        }
                }
            }
        }
    }

    private fun updateUI(state: MeetingUiState) {
        val prev = lastState
        lastState = state

        // ── 引擎标签（仅在引擎切换时变） ──
        if (prev == null || prev.asrEngineName != state.asrEngineName) {
            if (state.asrEngineName.isNotBlank()) {
                binding.tvEngineLabel.text = "当前引擎：${state.asrEngineName}"
                binding.tvEngineLabel.visibility = View.VISIBLE
            } else {
                binding.tvEngineLabel.visibility = View.GONE
            }
        }

        // ── 摘要进度条（仅在 LLM 生成时变） ──
        if (prev == null || prev.isGeneratingSummary != state.isGeneratingSummary ||
            prev.summaryProgress != state.summaryProgress) {
            if (state.isGeneratingSummary) {
                binding.progressSummary.visibility = View.VISIBLE
                binding.progressSummary.progress = (state.summaryProgress * 100).toInt()
            } else {
                binding.progressSummary.visibility = View.GONE
            }
        }

        // ── 连接状态条（仅 connect/disconnect/fail 时变） ──
        if (prev == null || prev.connectionState != state.connectionState ||
            prev.errorMessage != state.errorMessage) {
            when (state.connectionState) {
                ConnectionState.CONNECTING -> {
                    binding.layoutConnectionBanner.visibility = View.VISIBLE
                    binding.layoutConnectionBanner.setBackgroundColor(
                        cachedColor(R.color.status_paused))
                    binding.tvConnectionStatus.text = "正在启动语音引擎…"
                    binding.btnRetry.visibility = View.GONE
                }
                ConnectionState.RECONNECTING -> {
                    binding.layoutConnectionBanner.visibility = View.VISIBLE
                    binding.layoutConnectionBanner.setBackgroundColor(
                        cachedColor(R.color.status_paused))
                    binding.tvConnectionStatus.text = "网络中断，正在重连..."
                    binding.btnRetry.visibility = View.GONE
                }
                ConnectionState.FAILED -> {
                    binding.layoutConnectionBanner.visibility = View.VISIBLE
                    binding.layoutConnectionBanner.setBackgroundColor(
                        cachedColor(R.color.error_red))
                    binding.tvConnectionStatus.text =
                        state.errorMessage ?: "连接失败，请检查网络后重试"
                    binding.btnRetry.visibility = View.VISIBLE
                }
                else -> { binding.layoutConnectionBanner.visibility = View.GONE }
            }
        }

        // ── 会议开启/结束 → 全量刷一次（稀有事件；首次也走） ──
        // ── 会议进行中 → 只刷状态灯（isSpeaking / isPaused）──
        if (prev == null || prev.isMeetingActive != state.isMeetingActive) {
            // 会议状态切换：全量刷
            if (state.isMeetingActive) {
                binding.layoutTitleInput.visibility = View.GONE
                binding.btnStartEnd.text = "结束会议"
                binding.btnStartEnd.isEnabled = true
                binding.btnStartEnd.setBackgroundColor(
                    cachedColor(R.color.error_red))
                binding.btnOnline.visibility = View.GONE
                binding.btnOffline.visibility = View.GONE
                binding.btnPause.visibility = View.VISIBLE
                binding.btnPause.text = if (state.isPaused) "继续" else "暂停"
            } else {
                binding.layoutTitleInput.visibility = View.VISIBLE
                binding.btnStartEnd.text = "开始会议"
                binding.btnStartEnd.isEnabled = true
                binding.btnStartEnd.setBackgroundColor(
                    cachedColor(R.color.primary))
                binding.btnOnline.visibility = View.VISIBLE
                binding.btnOffline.visibility = View.VISIBLE
                binding.btnPause.visibility = View.GONE
                binding.tvTimer.text = "00:00:00"
            }
        } else if (state.isMeetingActive) {
            // 已激活，只看暂停/说话状态
            if (prev.isPaused != state.isPaused) {
                binding.btnPause.text = if (state.isPaused) "继续" else "暂停"
            }
        }

        // ── 状态灯（isSpeaking 每 ~200ms 翻转，只做 tint） ──
        if (state.isMeetingActive && (prev == null || prev.isSpeaking != state.isSpeaking ||
                prev.isPaused != state.isPaused || prev.isConnected != state.isConnected)) {
            binding.ivStatus.background?.setTint(
                cachedColor(
                    when {
                        state.isPaused -> R.color.status_paused
                        state.isSpeaking -> R.color.status_recording
                        state.isConnected -> R.color.status_connected_silent
                        else -> R.color.interim_text
                    }))
        } else if (!state.isMeetingActive && (prev == null || prev.isMeetingActive)) {
            binding.ivStatus.background?.setTint(
                cachedColor(R.color.interim_text))
        }

        // ── 错误条（仅在 errorMessage 变化时刷新） ──
        if (prev == null || prev.errorMessage != state.errorMessage) {
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
    }

    /**
     * 会议结束后的纪要审核弹窗：
     * 展示 AI 生成的纪要，支持编辑/保存/重新生成/导出。
     */
    private fun showSummaryReviewDialog(state: MeetingUiState) {
        if (!state.showSummaryReviewDialog || state.latestSummary.isBlank()) return

        val ctx = requireContext()
        val editText = EditText(ctx).apply {
            setText(state.latestSummary)
            minLines = 10
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            setPadding(32, 24, 32, 24)
            textSize = 14f
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("会议纪要")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                viewModel.saveSummaryForReview(editText.text.toString())
                Toast.makeText(ctx, "纪要已保存", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("重新生成", null)
            .setNegativeButton("导出纪要") { _, _ ->
                viewModel.saveSummaryForReview(editText.text.toString())
                viewModel.exportSummaryToFile(ctx, editText.text.toString())
            }
            .setOnDismissListener {
                // 旋转屏幕等配置变更会自动关闭弹窗——此时不标记为"已审核"，重建后重新弹出
                if (activity?.isChangingConfigurations != true) {
                    viewModel.dismissSummaryReview()
                }
            }
            .create()

        dialog.show()

        // "重新生成"点击后先关闭弹窗，再生完成后会重新弹出
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            viewModel.regenerateSummaryForReview()
            dialog.dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
