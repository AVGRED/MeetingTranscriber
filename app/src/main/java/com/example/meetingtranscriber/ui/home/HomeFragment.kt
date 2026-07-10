package com.example.meetingtranscriber.ui.home

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.meetingtranscriber.MainActivity
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.databinding.FragmentHomeBinding
import com.example.meetingtranscriber.engine.llm.ModelDownloadManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvGreeting.text = viewModel.greeting
        binding.tvDate.text = viewModel.todayDate

        setupClickListeners()
        observeState()
    }

    private fun setupClickListeners() {
        binding.btnRealtime.setOnClickListener {
            (requireActivity() as MainActivity).navigateToMeeting("realtime")
        }
        binding.btnOffline.setOnClickListener {
            (requireActivity() as MainActivity).navigateToMeeting("offline")
        }
        binding.btnViewAll.setOnClickListener {
            (requireActivity() as MainActivity).navigateToTab(R.id.nav_history)
        }
        binding.btnDownloadModel.setOnClickListener {
            viewModel.downloadQwenModel()
        }
    }

    private fun observeState() {
        val scope = viewLifecycleOwner.lifecycleScope

        // 引擎状态
        scope.launch {
            viewModel.asrEngineName.collect { name ->
                binding.tvAsrEngine.text = name
            }
        }
        scope.launch {
            viewModel.asrHasKey.collect { hasKey ->
                val dot = binding.dotAsrStatus.background as? GradientDrawable
                dot?.setColor(
                    ContextCompat.getColor(requireContext(),
                        if (hasKey) R.color.status_recording else R.color.status_paused
                    )
                )
            }
        }
        scope.launch {
            viewModel.llmEngineName.collect { name ->
                binding.tvLlmEngine.text = name
            }
        }
        scope.launch {
            viewModel.llmHasKey.collect { hasKey ->
                val dot = binding.dotLlmStatus.background as? GradientDrawable
                dot?.setColor(
                    ContextCompat.getColor(requireContext(),
                        if (hasKey) R.color.status_recording else R.color.status_paused
                    )
                )
            }
        }

        // 下载按钮显示/隐藏
        scope.launch {
            viewModel.isModelDownloadNeeded.collect { needed ->
                binding.btnDownloadModel.visibility =
                    if (needed) View.VISIBLE else View.GONE
            }
        }

        // 模型下载进度
        scope.launch {
            viewModel.modelDownloadProgress.collectLatest { state ->
                when (state.status) {
                    ModelDownloadManager.DownloadState.Status.IDLE,
                    ModelDownloadManager.DownloadState.Status.COMPLETED -> {
                        binding.progressModelDownload.visibility = View.GONE
                        binding.tvDownloadStatus.visibility = View.GONE
                    }
                    ModelDownloadManager.DownloadState.Status.DOWNLOADING -> {
                        binding.progressModelDownload.visibility = View.VISIBLE
                        binding.progressModelDownload.progress = state.percent
                        binding.tvDownloadStatus.visibility = View.VISIBLE
                        binding.tvDownloadStatus.text =
                            "Qwen 模型下载中 ${state.percent}% (${state.downloadedMB.toInt()}MB / ${state.totalMB.toInt()}MB)"
                    }
                    ModelDownloadManager.DownloadState.Status.VERIFYING -> {
                        binding.progressModelDownload.visibility = View.VISIBLE
                        binding.progressModelDownload.isIndeterminate = true
                        binding.tvDownloadStatus.visibility = View.VISIBLE
                        binding.tvDownloadStatus.text = "正在校验模型文件..."
                    }
                    ModelDownloadManager.DownloadState.Status.ERROR -> {
                        binding.progressModelDownload.visibility = View.GONE
                        binding.tvDownloadStatus.visibility = View.VISIBLE
                        binding.tvDownloadStatus.text = "下载失败: ${state.errorMessage ?: "未知错误"}"
                    }
                    else -> {}
                }
            }
        }

        // 统计数字
        scope.launch {
            viewModel.meetingCount.collect { count ->
                binding.tvMeetingCount.text = count.toString()
            }
        }
        scope.launch {
            viewModel.totalMinutes.collect { mins ->
                binding.tvTotalMinutes.text = mins.toString()
            }
        }
        scope.launch {
            viewModel.freeStorageGB.collect { gb ->
                binding.tvFreeStorage.text = gb
            }
        }

        // 最近会议
        scope.launch {
            viewModel.recentMeetings.collect { meetings ->
                renderRecentMeetings(meetings)
            }
        }
    }

    private fun renderRecentMeetings(meetings: List<MeetingInfo>) {
        val container = binding.layoutRecentMeetings
        container.removeAllViews()

        if (meetings.isEmpty()) {
            binding.tvEmptyRecent.visibility = View.VISIBLE
            return
        }
        binding.tvEmptyRecent.visibility = View.GONE

        for (meeting in meetings) {
            val card = layoutInflater.inflate(R.layout.item_meeting_home, container, false)
            card.findViewById<TextView>(R.id.tv_meeting_title).text = meeting.title
            card.findViewById<TextView>(R.id.tv_meeting_info).text =
                "${meeting.date} · ${meeting.durationSeconds / 60}分钟 · ${meeting.speakerCount}人"
            card.setOnClickListener {
                // 跳转历史 tab 并打开详情（通过 MainActivity）
                (requireActivity() as MainActivity).navigateToMeetingDetail(meeting.id)
            }
            container.addView(card)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
