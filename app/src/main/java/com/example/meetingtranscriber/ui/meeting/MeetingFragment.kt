package com.example.meetingtranscriber.ui.meeting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.audio.AudioCaptureService
import com.example.meetingtranscriber.databinding.FragmentMeetingBinding
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
            viewModel.startMeeting()
        } else {
            Toast.makeText(requireContext(), "录音权限是必需的", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMeetingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupClickListeners()
        observeState()
    }

    private fun setupRecyclerView() {
        adapter = TranscriptAdapter()
        binding.rvTranscript.adapter = adapter
        binding.rvTranscript.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
    }

    private fun setupClickListeners() {
        // 演示模式按钮
        binding.btnDemo.setOnClickListener {
            viewModel.startDemo()
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
    }

    private fun startRealMeeting() {
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val intent = Intent(requireContext(), AudioCaptureService::class.java)
            ContextCompat.startForegroundService(requireContext(), intent)
            viewModel.startMeeting()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.segments.collectLatest { segments ->
                adapter.submitList(segments) {
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
            }
        }
    }

    private fun updateUI(state: MeetingUiState) {
        if (state.isMeetingActive) {
            binding.btnStartEnd.text = "结束会议"
            binding.btnStartEnd.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.error_red)
            )
            binding.btnDemo.visibility = View.GONE
            binding.btnPause.visibility = View.VISIBLE
            binding.btnPause.text = if (state.isPaused) "继续" else "暂停"

            // 演示模式标签
            binding.tvDemoBadge.visibility = if (state.isDemoMode) View.VISIBLE else View.GONE

            val h = state.elapsedSeconds / 3600
            val m = (state.elapsedSeconds % 3600) / 60
            val s = state.elapsedSeconds % 60
            binding.tvTimer.text = "%02d:%02d:%02d".format(h, m, s)

            binding.ivStatus.background?.setTint(
                ContextCompat.getColor(
                    requireContext(),
                    if (state.isPaused) R.color.status_paused
                    else if (state.isConnected) R.color.status_recording
                    else R.color.status_paused
                )
            )
        } else {
            binding.btnStartEnd.text = "开始会议"
            binding.btnStartEnd.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.primary)
            )
            binding.btnDemo.visibility = View.VISIBLE
            binding.btnPause.visibility = View.GONE
            binding.tvDemoBadge.visibility = View.GONE
            binding.tvTimer.text = "00:00:00"
            binding.ivStatus.background?.setTint(
                ContextCompat.getColor(requireContext(), R.color.interim_text)
            )
        }

        if (state.errorMessage != null) {
            Toast.makeText(requireContext(), state.errorMessage, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
