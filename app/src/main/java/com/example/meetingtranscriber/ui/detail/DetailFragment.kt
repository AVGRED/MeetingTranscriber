package com.example.meetingtranscriber.ui.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.databinding.FragmentDetailBinding
import com.example.meetingtranscriber.ui.meeting.TranscriptAdapter
import kotlinx.coroutines.launch

class DetailFragment : Fragment() {

    companion object {
        private const val ARG_MEETING_ID = "meeting_id"

        fun newInstance(meetingId: Long): DetailFragment {
            return DetailFragment().apply {
                arguments = Bundle().apply { putLong(ARG_MEETING_ID, meetingId) }
            }
        }
    }

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DetailViewModel by viewModels()
    private lateinit var adapter: TranscriptAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val meetingId = arguments?.getLong(ARG_MEETING_ID, 0) ?: 0
        if (meetingId == 0L) {
            parentFragmentManager.popBackStack()
            return
        }

        adapter = TranscriptAdapter()
        binding.rvTranscript.adapter = adapter
        binding.rvTranscript.layoutManager = LinearLayoutManager(requireContext())

        binding.btnExport.setOnClickListener { exportToTxt() }

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // Phase 2: 搜索转写内容
                true
            } else {
                false
            }
        }

        viewModel.loadMeeting(meetingId)

        // 合并两个 collector 到一个协程，避免并发 UI 更新
        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                viewModel.meeting.collect { meeting ->
                    if (meeting != null) {
                        binding.tvTitle.text = meeting.title
                        binding.tvInfo.text = "${meeting.formattedStartTime} · ${meeting.formattedDuration}"
                        binding.tvSpeakers.text = "${meeting.speakerCount} 位说话人 · ${meeting.segmentCount} 条记录"
                        if (!meeting.summary.isNullOrBlank()) {
                            binding.tvSummary.visibility = View.VISIBLE
                            binding.tvSummary.text = meeting.summary
                        }
                    }
                }
            }
            viewModel.segments.collect { segments ->
                adapter.submitList(segments)
            }
        }
    }

    private fun exportToTxt() {
        val meeting = viewModel.meeting.value ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val segments = viewModel.segments.value
            if (segments.isEmpty()) {
                Toast.makeText(requireContext(), "暂无转写内容", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val content = buildString {
                appendLine("=".repeat(50))
                appendLine(meeting.title)
                appendLine("时间: ${meeting.formattedStartTime}")
                appendLine("时长: ${meeting.formattedDuration}")
                appendLine("说话人数: ${meeting.speakerCount}")
                appendLine("=".repeat(50))
                appendLine()

                if (!meeting.summary.isNullOrBlank()) {
                    appendLine("【会议纪要】")
                    appendLine(meeting.summary)
                    appendLine()
                }

                appendLine("【会议转写】")
                segments.forEach { segment ->
                    appendLine("${segment.displaySpeaker} [${segment.formattedTime}]: ${segment.text}")
                    appendLine()
                }
            }

            val fileName = "${meeting.title.replace(Regex("[/\\\\:*?\"<>|]"), "_")}.txt"
            val dir = requireContext().getExternalFilesDir(null)
            if (dir == null) {
                Toast.makeText(requireContext(), "无法访问存储", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val file = java.io.File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)
            Toast.makeText(requireContext(), "已导出到: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
