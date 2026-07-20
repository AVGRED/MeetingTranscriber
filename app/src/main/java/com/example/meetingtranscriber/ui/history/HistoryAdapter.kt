package com.example.meetingtranscriber.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.databinding.ItemMeetingHistoryBinding

class HistoryAdapter(
    private val onItemClick: (MeetingInfo) -> Unit,
    private val onDeleteClick: (MeetingInfo) -> Unit,
    private val onRestoreClick: ((MeetingInfo) -> Unit)? = null,
    private val onExportClick: ((MeetingInfo) -> Unit)? = null
) : ListAdapter<MeetingInfo, HistoryAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMeetingHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemMeetingHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(meeting: MeetingInfo) {
            binding.tvTitle.text = meeting.title
            binding.tvTime.text = meeting.formattedStartTime
            binding.tvDuration.text = meeting.formattedDuration

            // ── 内容状态指示器：录音 · 纪要 · 转写（始终显示三项）──
            val ctx = binding.root.context
            val hasRecording = !meeting.audioFilePath.isNullOrBlank()
            val hasSummary = !meeting.summary.isNullOrBlank()
            val hasTranscripts = meeting.segmentCount > 0

            // 录音指示器 — 始终显示
            binding.layoutHasRecording.visibility = View.VISIBLE
            val recordingDot = binding.indicatorRecordingDot
            val recordingText = binding.indicatorRecordingText
            if (hasRecording) {
                recordingDot.background?.setTint(ContextCompat.getColor(ctx, R.color.indicator_recording))
                recordingText.text = "录音"
                recordingText.setTextColor(ContextCompat.getColor(ctx, R.color.indicator_recording))
            } else {
                recordingDot.background?.setTint(ContextCompat.getColor(ctx, R.color.interim_text))
                recordingText.text = "无录音"
                recordingText.setTextColor(ContextCompat.getColor(ctx, R.color.interim_text))
            }

            // 纪要指示器 — 始终显示
            binding.layoutHasSummary.visibility = View.VISIBLE
            val summaryDot = binding.indicatorSummaryDot
            val summaryText = binding.indicatorSummaryText
            if (hasSummary) {
                summaryDot.background?.setTint(ContextCompat.getColor(ctx, R.color.indicator_summary))
                summaryText.text = "纪要"
                summaryText.setTextColor(ContextCompat.getColor(ctx, R.color.indicator_summary))
            } else {
                summaryDot.background?.setTint(ContextCompat.getColor(ctx, R.color.interim_text))
                summaryText.text = "无纪要"
                summaryText.setTextColor(ContextCompat.getColor(ctx, R.color.interim_text))
            }

            // 分隔点始终显示
            binding.tvDot1.visibility = View.VISIBLE
            binding.tvDot2.visibility = View.VISIBLE

            // 转写指示器 — 始终显示
            val speakerText = if (meeting.speakerCount > 0) {
                "${meeting.speakerCount} 位说话人"
            } else {
                "0 位说话人"
            }
            val segmentText = if (hasTranscripts) {
                "${meeting.segmentCount} 条转写"
            } else {
                "暂无转写"
            }
            binding.tvSpeakers.text = "$speakerText · $segmentText"

            // 状态点统一灰色
            binding.ivStatus.background?.setTint(
                ContextCompat.getColor(ctx, R.color.interim_text)
            )

            binding.root.setOnClickListener { onItemClick(meeting) }

            // 导出按钮：进行中的会议不显示
            binding.btnExport.visibility = if (meeting.isOngoing) View.GONE else View.VISIBLE
            binding.btnExport.setOnClickListener { onExportClick?.invoke(meeting) }

            // 回收站模式：显示恢复按钮
            if (meeting.isArchived) {
                binding.btnDelete.contentDescription = "永久删除"
                binding.btnDelete.setOnClickListener { onDeleteClick(meeting) }
                binding.btnRestore.visibility = View.VISIBLE
                binding.btnRestore.setOnClickListener { onRestoreClick?.invoke(meeting) }
            } else {
                binding.btnDelete.contentDescription = "删除"
                binding.btnDelete.setOnClickListener { onDeleteClick(meeting) }
                binding.btnRestore.visibility = View.GONE
            }
            // btnUpload 保留在布局中，供后续云端上传功能使用
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<MeetingInfo>() {
        override fun areItemsTheSame(old: MeetingInfo, new: MeetingInfo) = old.id == new.id

        override fun areContentsTheSame(old: MeetingInfo, new: MeetingInfo): Boolean =
            old.title == new.title &&
            old.endTime == new.endTime &&
            old.segmentCount == new.segmentCount &&
            old.speakerCount == new.speakerCount &&
            old.isArchived == new.isArchived &&
            old.audioFilePath == new.audioFilePath &&
            old.summary == new.summary
    }
}
