package com.example.meetingtranscriber.ui.history

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager

import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.ui.detail.DetailFragment
import com.example.meetingtranscriber.ui.export.ExportHelper
import com.example.meetingtranscriber.ui.export.QrShareDialog
import com.example.meetingtranscriber.databinding.FragmentHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryFragment : Fragment() {

    companion object {
        val PRESET_TAGS = listOf("全部", "产品", "技术", "客户", "人事", "其他")
    }

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = HistoryAdapter(
            onItemClick = { meeting ->
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, DetailFragment.newInstance(meeting.id))
                    .addToBackStack(null)
                    .commit()
            },
            onDeleteClick = { meeting ->
                if (viewModel.showArchived.value) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("永久删除")
                        .setMessage("确定要永久删除「${meeting.title}」吗？\n此操作不可恢复。")
                        .setPositiveButton("删除") { _, _ -> viewModel.permanentDelete(meeting.id) }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    viewModel.archiveMeeting(meeting.id)
                    Toast.makeText(requireContext(), "已移至回收站", Toast.LENGTH_SHORT).show()
                }
            },
            onRestoreClick = { meeting ->
                viewModel.restoreMeeting(meeting.id)
                Toast.makeText(requireContext(), "已恢复", Toast.LENGTH_SHORT).show()
            },
            onExportClick = { meeting -> showExportDialog(meeting) }
        )

        binding.rvHistory.adapter = adapter
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
            binding.swipeRefresh.postDelayed({ binding.swipeRefresh.isRefreshing = false }, 800)
        }

        // 回收站切换
        binding.btnRecycleBin.setOnClickListener {
            viewModel.toggleArchived()
        }

        // 搜索栏（300ms debounce）
        var searchJob: Job? = null
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300L)
                    viewModel.setSearchQuery(s?.toString() ?: "")
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 标签筛选 chips
        setupTagChips()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.meetings.collect { meetings ->
                adapter.submitList(meetings)
                binding.tvEmpty.visibility = if (meetings.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.showArchived.collect { archived ->
                binding.btnRecycleBin.text = if (archived) "返回列表" else "回收站"
                binding.layoutTagChips.visibility = if (archived) View.GONE else View.VISIBLE
            }
        }
    }

    private fun setupTagChips() {
        for (tag in PRESET_TAGS) {
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = tag
                isCheckable = true
                isCheckedIconVisible = false
                setOnClickListener {
                    val selected = if (tag == "全部") null else tag
                    viewModel.setTagFilter(selected)
                }
            }
            binding.chipGroupTags.addView(chip)
        }
    }

    private fun showExportDialog(meeting: com.example.meetingtranscriber.data.model.MeetingInfo) {
        val formats = arrayOf("TXT 文本", "Word 文档", "PDF 文件", "扫码下载（文档+录音）")
        val mimeTypes = arrayOf("text/plain", "application/msword", "application/pdf")

        AlertDialog.Builder(requireContext())
            .setTitle("导出「${meeting.title}」")
            .setItems(formats) { _, which ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val (m, segments) = viewModel.loadForExport(meeting.id)
                    val mInfo = m ?: return@launch
                    if (segments.isEmpty()) {
                        Toast.makeText(requireContext(), "暂无转写内容", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    if (which == 3) {
                        QrShareDialog.show(this@HistoryFragment, mInfo, segments)
                        return@launch
                    }
                    // 文件生成移到 IO：长会议主线程画 PDF/拼文本会冻结数秒
                    val file = withContext(Dispatchers.IO) {
                        when (which) {
                            0 -> ExportHelper.exportTxt(requireContext(), mInfo, segments)
                            1 -> ExportHelper.exportWord(requireContext(), mInfo, segments)
                            2 -> ExportHelper.exportPdf(requireContext(), mInfo, segments)
                            else -> null
                        }
                    }
                    if (file != null) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("导出成功")
                            .setMessage("已保存到:\n${file.absolutePath}")
                            .setPositiveButton("分享") { _, _ ->
                                ExportHelper.shareFile(requireContext(), file, mimeTypes[which])
                            }
                            .setNegativeButton("关闭", null)
                            .show()
                    } else {
                        Toast.makeText(requireContext(), "导出失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
