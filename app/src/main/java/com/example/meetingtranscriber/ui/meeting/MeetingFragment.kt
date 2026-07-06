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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.audio.AudioCaptureService
import com.example.meetingtranscriber.data.db.RecoveryStateEntity
import com.example.meetingtranscriber.databinding.FragmentMeetingBinding
import com.example.meetingtranscriber.network.ConnectionState
import com.example.meetingtranscriber.util.SUPPORTED_LANGUAGES
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MeetingFragment : Fragment() {

    private var _binding: FragmentMeetingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MeetingViewModel by viewModels()
    private lateinit var adapter: TranscriptAdapter

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMeetingWithService()
        } else {
            Toast.makeText(requireContext(), "录音权限是必需的", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 无论通知权限是否被授予，都继续启动前台 Service
        checkAudioPermissionAndStart()
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
        // 预填默认会议标题
        binding.etMeetingTitle.setText(
            "会议_${java.text.SimpleDateFormat("MM-dd_HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        )

        // 离线录音按钮
        binding.btnOffline.setOnClickListener {
            viewModel.startOfflineMeeting(binding.etMeetingTitle.text.toString().trim(), getSelectedLanguage(), getSelectedTag())
        }

        // 演示模式按钮
        binding.btnDemo.setOnClickListener {
            viewModel.startDemo(binding.etMeetingTitle.text.toString().trim())
        }

        // 开始/结束 按钮
        binding.btnStartEnd.setOnClickListener {
            if (viewModel.uiState.value.isMeetingActive) {
                viewModel.endMeeting()
            } else {
                startRealMeeting()
            }
        }

        // 暂停/继续 按钮
        binding.btnPause.setOnClickListener {
            viewModel.togglePause()
        }

        binding.btnRetry.setOnClickListener {
            viewModel.retryConnection()
        }
    }

    private fun startRealMeeting() {
        // Android 13+ 先请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotification = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotification) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkAudioPermissionAndStart()
    }

    fun startUpload(meetingId: Long) {
        viewModel.uploadOfflineMeeting(meetingId)
    }

    fun recoverFromCrash(state: RecoveryStateEntity) {
        viewModel.recoverFromCrash(state)
    }

    private fun checkAudioPermissionAndStart() {
        val hasAudio = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudio) {
            startMeetingWithService()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMeetingWithService() {
        try {
            val intent = Intent(requireContext(), AudioCaptureService::class.java)
            ContextCompat.startForegroundService(requireContext(), intent)
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "无法启动前台服务：${e.message}", Toast.LENGTH_LONG).show()
            return
        } catch (e: IllegalStateException) {
            // Android 14+ 后台启动限制
            Toast.makeText(requireContext(), "应用在后台无法启动前台服务，请返回前台后重试", Toast.LENGTH_LONG).show()
            return
        }
        val title = binding.etMeetingTitle.text.toString().trim().ifBlank { "会议" }
        val language = getSelectedLanguage()
        val tag = getSelectedTag()
        viewModel.startMeeting(title, language, tag)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.segments.collectLatest { segments ->
                adapter.submitSegments(segments) {
                    if (segments.isNotEmpty()) {
                        binding.rvTranscript.smoothScrollToPosition(adapter.itemCount - 1)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUI(state)
                adapter.setInterimText(state.interimText.takeIf { it.isNotBlank() })

                if (state.showOfflineUploadPrompt) {
                    showOfflineUploadDialog()
                }
            }
        }
    }

    private fun showOfflineUploadDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("离线录音已保存")
            .setMessage("是否立即上传到云端进行AI转写？")
            .setPositiveButton("上传") { _, _ ->
                viewModel.dismissUploadPrompt()
                // 切换到历史页面找到刚结束的会议
                requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                    R.id.bottom_navigation
                )?.selectedItemId = R.id.nav_history
            }
            .setNegativeButton("稍后") { _, _ ->
                viewModel.dismissUploadPrompt()
            }
            .setOnCancelListener { viewModel.dismissUploadPrompt() }
            .show()
    }

    private fun updateUI(state: MeetingUiState) {
        // 连接状态横幅
        when (state.connectionState) {
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
                binding.tvConnectionStatus.text = "连接失败，请检查网络后重试"
                binding.btnRetry.visibility = View.VISIBLE
            }
            else -> {
                binding.layoutConnectionBanner.visibility = View.GONE
            }
        }

        if (state.isMeetingActive) {
            binding.layoutTitleInput.visibility = View.GONE
            binding.btnStartEnd.text = if (state.isUploading) "上传中..." else "结束会议"
            binding.btnStartEnd.isEnabled = !state.isUploading
            binding.btnStartEnd.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.error_red)
            )
            binding.btnDemo.visibility = View.GONE
            binding.btnOffline.visibility = View.GONE
            binding.btnPause.visibility = if (state.isOfflineMode || state.isUploading) View.GONE else View.VISIBLE
            binding.btnPause.text = if (state.isPaused) "继续" else "暂停"

            // 演示/离线模式标签
            binding.tvDemoBadge.visibility = when {
                state.isDemoMode -> { binding.tvDemoBadge.text = "● 演示模式 — 模拟 3 人会议对话"; View.VISIBLE }
                state.isOfflineMode -> { binding.tvDemoBadge.text = "● 离线录音 — 音频保存到本地"; View.VISIBLE }
                state.isUploading -> { binding.tvDemoBadge.text = "● 正在上传离线录音到云端转写..."; View.VISIBLE }
                else -> View.GONE
            }

            val h = state.elapsedSeconds / 3600
            val m = (state.elapsedSeconds % 3600) / 60
            val s = state.elapsedSeconds % 60
            binding.tvTimer.text = "%02d:%02d:%02d".format(h, m, s)

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
            binding.btnDemo.visibility = View.VISIBLE
            binding.btnOffline.visibility = View.VISIBLE
            binding.btnPause.visibility = View.GONE
            binding.tvDemoBadge.visibility = View.GONE
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
