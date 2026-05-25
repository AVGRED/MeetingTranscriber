package com.example.meetingtranscriber.ui.history

import android.view.LayoutInflater
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
    private val onDeleteClick: (MeetingInfo) -> Unit
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
            binding.tvSpeakers.text = "${meeting.speakerCount} 位说话人 · ${meeting.segmentCount} 条记录"

            // 进行中的会议显示绿点
            if (meeting.isOngoing) {
                binding.ivStatus.background?.setTint(
                    ContextCompat.getColor(binding.root.context, R.color.status_recording)
                )
            } else {
                binding.ivStatus.background?.setTint(
                    ContextCompat.getColor(binding.root.context, R.color.interim_text)
                )
            }

            binding.root.setOnClickListener { onItemClick(meeting) }
            binding.btnDelete.setOnClickListener { onDeleteClick(meeting) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<MeetingInfo>() {
        override fun areItemsTheSame(old: MeetingInfo, new: MeetingInfo) = old.id == new.id
        override fun areContentsTheSame(old: MeetingInfo, new: MeetingInfo) =
            old.title == new.title && old.endTime == new.endTime && old.segmentCount == new.segmentCount
    }
}
