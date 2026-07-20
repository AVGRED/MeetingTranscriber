package com.example.meetingtranscriber.ui.detail

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.meetingtranscriber.data.model.TranscriptSegment
import com.example.meetingtranscriber.databinding.FragmentDetailBinding
import com.example.meetingtranscriber.ui.export.ExportHelper
import com.example.meetingtranscriber.util.DebounceTextWatcher
import com.example.meetingtranscriber.ui.meeting.TranscriptAdapter
import kotlinx.coroutines.flow.combine
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
    private var playerController: AudioPlayerController? = null

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

        adapter = TranscriptAdapter(onSpeakerClick = { segment -> showRenameDialog(segment) })
        binding.rvTranscript.adapter = adapter
        binding.rvTranscript.layoutManager = LinearLayoutManager(requireContext())

        playerController = AudioPlayerController(binding, viewLifecycleOwner.lifecycleScope)

        // ── 顶栏 ──
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnExport.setOnClickListener { showExportDialog() }

        // ── 搜索栏折叠/展开 ──
        binding.btnSearchToggle.setOnClickListener {
            binding.layoutSearchBar.visibility =
                if (binding.layoutSearchBar.visibility == View.VISIBLE) View.GONE
                else View.VISIBLE
        }

        // ── 纪要操作 ──
        binding.btnSaveSummary.setOnClickListener {
            val text = binding.etSummary.text?.toString() ?: ""
            viewModel.saveSummary(text)
            Toast.makeText(requireContext(), "纪要已保存", Toast.LENGTH_SHORT).show()
        }

        binding.btnRegenerateSummary.setOnClickListener {
            when {
                viewModel.originalSummary.value == null -> {
                    if (viewModel.segments.value.isEmpty()) {
                        Toast.makeText(requireContext(), "暂无转写内容，无法生成纪要", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "正在生成纪要…", Toast.LENGTH_SHORT).show()
                        viewModel.regenerateSummary()
                    }
                }
                viewModel.isSummaryEdited.value -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle("撤销修改")
                        .setMessage("恢复到 AI 生成的原始纪要？")
                        .setPositiveButton("恢复") { _, _ -> viewModel.restoreOriginal() }
                        .setNegativeButton("取消", null)
                        .show()
                }
                else -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle("重新生成纪要")
                        .setMessage("当前纪要将被 AI 重新生成的内容覆盖，是否继续？")
                        .setPositiveButton("覆盖") { _, _ -> viewModel.regenerateSummary() }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }

        binding.etSummary.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val current = s?.toString() ?: ""
                val original = viewModel.originalSummary.value
                viewModel.setSummaryEdited(
                    if (original == null) current.isNotBlank() else current != original
                )
            }
        })

        // ── 搜索 ──
        binding.etSearch.addTextChangedListener(
            DebounceTextWatcher(viewLifecycleOwner.lifecycleScope, 300) { query ->
                viewModel.search(query)
            }
        )
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.search(binding.etSearch.text?.toString() ?: "")
                true
            } else false
        }

        // ── 规整 ──
        binding.btnFormat.setOnClickListener {
            if (viewModel.hasFormatBackup()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("撤销文本规整")
                    .setMessage("恢复到规整前的原始文本？")
                    .setPositiveButton("撤销") { _, _ -> viewModel.undoFormat() }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle("文本规整")
                    .setMessage("将对所有转写文本应用数字格式化和标点规范化（可撤销）")
                    .setPositiveButton("执行") { _, _ -> viewModel.formatAllSegments() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        viewModel.loadMeeting(meetingId)

        // ── 数据收集 ──
        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                viewModel.meeting.collect { meeting ->
                    if (meeting != null) {
                        binding.tvTitle.text = meeting.title
                        binding.tvInfo.text = "${meeting.formattedStartTime} · ${meeting.formattedDuration}"
                        binding.tvSpeakers.text = "${meeting.speakerCount}位说话人 · ${meeting.segmentCount}条"
                        binding.layoutSummary.visibility = View.VISIBLE

                        if (meeting.summary.isNullOrBlank()) {
                            binding.etSummary.hint = "暂无纪要，可点击「重新生成」"
                            binding.btnRegenerateSummary.text = "生成纪要"
                        } else if (!viewModel.isSummaryEdited.value) {
                            binding.etSummary.setText(meeting.summary)
                        }
                        playerController?.bind(meeting.audioFilePath)
                    }
                }
            }
            launch {
                viewModel.isSummaryEdited.collect { edited ->
                    binding.btnSaveSummary.isEnabled = edited
                    if (viewModel.originalSummary.value != null) {
                        binding.btnRegenerateSummary.text = if (edited) "撤销修改" else "重新生成"
                    }
                }
            }
            launch {
                viewModel.originalSummary.collect { original ->
                    if (original != null) {
                        if (!viewModel.isSummaryEdited.value) {
                            binding.etSummary.setText(original)
                        }
                        binding.btnRegenerateSummary.text =
                            if (viewModel.isSummaryEdited.value) "撤销修改" else "重新生成"
                    }
                }
            }
            launch {
                viewModel.segments.collect { segs ->
                    binding.btnFormat.visibility = if (segs.isNotEmpty()) View.VISIBLE else View.GONE
                    binding.btnFormat.text = if (viewModel.hasFormatBackup()) "撤销规整" else "规整"
                }
            }
            launch {
                combine(viewModel.searchQuery, viewModel.segments, viewModel.searchResults) { query, segments, results ->
                    Triple(query, segments, results)
                }.collect { (query, segments, results) ->
                    adapter.setSearchQuery(query.takeIf { it.isNotBlank() })
                    if (query.isBlank()) {
                        adapter.submitSegments(segments)
                    } else {
                        adapter.submitSegments(results ?: emptyList())
                    }
                }
            }
        }
    }

    private fun showRenameDialog(segment: TranscriptSegment) {
        val input = EditText(requireContext())
        input.setText(segment.displaySpeaker)
        input.selectAll()

        AlertDialog.Builder(requireContext())
            .setTitle("重命名说话人")
            .setMessage("将「${segment.displaySpeaker}」的所有发言重命名为：")
            .setView(input)
            .setPositiveButton("确认") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank() && newName != segment.displaySpeaker) {
                    viewModel.renameSpeaker(segment.speakerId, newName)
                    Toast.makeText(requireContext(), "已重命名为「$newName」", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showExportDialog() {
        val meeting = viewModel.meeting.value ?: return
        ExportHelper.showExportDialog(this, meeting, viewModel.segments.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        playerController?.release()
        playerController = null
        _binding = null
    }
}
