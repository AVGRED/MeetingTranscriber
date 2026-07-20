package com.example.meetingtranscriber.ui.history

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.ui.detail.DetailFragment
import com.example.meetingtranscriber.ui.export.ExportHelper
import com.example.meetingtranscriber.ui.export.QrShareDialog
import com.example.meetingtranscriber.databinding.FragmentHistoryBinding
import com.example.meetingtranscriber.util.DebounceTextWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryFragment : Fragment() {

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
            // 给一个短暂延迟让用户感知刷新操作
            binding.swipeRefresh.postDelayed({ binding.swipeRefresh.isRefreshing = false }, 800)
        }

        // 回收站切换
        binding.btnRecycleBin.setOnClickListener {
            viewModel.toggleArchived()
        }

        // 搜索栏（300ms debounce）
        binding.etSearch.addTextChangedListener(
            DebounceTextWatcher(viewLifecycleOwner.lifecycleScope, 300) { query ->
                viewModel.setSearchQuery(query)
            }
        )

        // ── 数据收集：使用 STARTED 确保 Tab 切换回来时立刻恢复 ──
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 合并 meetings + initialized：防止初始空列表短暂显示空状态
                launch {
                    combine(viewModel.meetings, viewModel.initialized) { meetings, init ->
                        Pair(meetings, init)
                    }.collect { (meetings, initialized) ->
                        adapter.submitList(meetings)
                        if (initialized) {
                            binding.layoutEmpty.visibility =
                                if (meetings.isEmpty()) View.VISIBLE else View.GONE
                        }
                    }
                }
                // 回收站状态
                launch {
                    viewModel.showArchived.collect { archived ->
                        binding.btnRecycleBin.text = if (archived) "返回列表" else "回收站"
                    }
                }
                // 统计卡片
                launch {
                    viewModel.meetingCount.collect { count ->
                        binding.tvMeetingCount.text = count.toString()
                    }
                }
                launch {
                    viewModel.recordingCount.collect { count ->
                        binding.tvRecordingCount.text = count.toString()
                    }
                }
                launch {
                    viewModel.recordingLabel.collect { label ->
                        binding.tvRecordingLabel.text = label
                    }
                }
                launch {
                    viewModel.freeStorageGB.collect { gb ->
                        binding.tvFreeStorage.text = gb
                    }
                }
            }
        }
    }

    private fun showExportDialog(meeting: com.example.meetingtranscriber.data.model.MeetingInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            val (m, segments) = viewModel.loadForExport(meeting.id)
            val mInfo = m ?: return@launch
            ExportHelper.showExportDialog(this@HistoryFragment, mInfo, segments)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
