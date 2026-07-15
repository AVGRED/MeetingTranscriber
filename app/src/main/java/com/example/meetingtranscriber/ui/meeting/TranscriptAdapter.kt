package com.example.meetingtranscriber.ui.meeting

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.data.model.TranscriptSegment

sealed class AdapterItem {
    data class TopicHeader(val topicId: Int, val segmentCount: Int) : AdapterItem()
    data class SegmentItem(val segment: TranscriptSegment) : AdapterItem()
}

class TranscriptAdapter(
    private val onSpeakerClick: ((TranscriptSegment) -> Unit)? = null
) : ListAdapter<AdapterItem, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val VIEW_TYPE_SEGMENT = 0
        private const val VIEW_TYPE_INTERIM = 1
        private const val VIEW_TYPE_TOPIC_HEADER = 2

        private val SPEAKER_COLORS = intArrayOf(
            R.color.speaker_1,
            R.color.speaker_2,
            R.color.speaker_3,
            R.color.speaker_4,
            R.color.speaker_5,
            R.color.speaker_6,
            R.color.speaker_7,
            R.color.speaker_8,
        )
    }

    private var interimText: String? = null
    private var searchQuery: String? = null

    fun setSearchQuery(query: String?) {
        searchQuery = if (query.isNullOrBlank()) null else query.lowercase()
    }

    fun setInterimText(text: String?) {
        val newText = if (text.isNullOrBlank()) null else text
        if (newText == interimText) return  // 文本未变（如计时器 tick 触发）不重绑
        val hadInterim = interimText != null
        val hasInterim = newText != null
        interimText = newText

        when {
            !hadInterim && hasInterim -> notifyItemInserted(itemCount)
            hadInterim && !hasInterim -> notifyItemRemoved(itemCount)
            hadInterim && hasInterim -> notifyItemChanged(itemCount - 1)
            else -> {}
        }
    }

    fun submitSegments(segments: List<TranscriptSegment>, commitCallback: Runnable? = null) {
        val items = buildAdapterItems(segments)
        submitList(items, commitCallback)
    }

    private fun buildAdapterItems(segments: List<TranscriptSegment>): List<AdapterItem> {
        if (segments.isEmpty()) return emptyList()
        // 实时 append 与 DB ORDER BY 两个来源本就有序：先 O(n) 检查，避免每句全量排序
        var isSorted = true
        for (i in 1 until segments.size) {
            if (segments[i].startTimeMs < segments[i - 1].startTimeMs) { isSorted = false; break }
        }
        val sorted = if (isSorted) segments else segments.sortedBy { it.startTimeMs }
        // 各话题段数一次算好，避免每遇新话题全列表 count（O(话题数×N)）
        val topicCounts = sorted.groupingBy { it.topicId }.eachCount()
        val result = ArrayList<AdapterItem>(sorted.size + topicCounts.size)
        var currentTopic = -1
        for (seg in sorted) {
            if (seg.topicId != 0 && seg.topicId != currentTopic) {
                currentTopic = seg.topicId
                result.add(AdapterItem.TopicHeader(currentTopic, topicCounts[currentTopic] ?: 0))
            }
            result.add(AdapterItem.SegmentItem(seg))
        }
        return result
    }

    override fun getItemCount(): Int {
        return super.getItemCount() + if (interimText != null) 1 else 0
    }

    override fun getItemViewType(position: Int): Int {
        return if (interimText != null && position == itemCount - 1) {
            VIEW_TYPE_INTERIM
        } else {
            val item = getItem(position)
            when (item) {
                is AdapterItem.TopicHeader -> VIEW_TYPE_TOPIC_HEADER
                is AdapterItem.SegmentItem -> VIEW_TYPE_SEGMENT
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_TOPIC_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_topic_header, parent, false)
                TopicHeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_transcript_segment, parent, false)
                SegmentViewHolder(view, onSpeakerClick)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            VIEW_TYPE_INTERIM -> {
                (holder as SegmentViewHolder).bindInterim(interimText ?: "", holder.itemView.context)
            }
            VIEW_TYPE_TOPIC_HEADER -> {
                val item = getItem(position) as AdapterItem.TopicHeader
                (holder as TopicHeaderViewHolder).bind(item)
            }
            VIEW_TYPE_SEGMENT -> {
                val item = getItem(position) as AdapterItem.SegmentItem
                (holder as SegmentViewHolder).bind(item.segment, holder.itemView.context, searchQuery)
            }
        }
    }

    class TopicHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.tv_topic_title)
        private val infoText: TextView = itemView.findViewById(R.id.tv_topic_info)

        fun bind(header: AdapterItem.TopicHeader) {
            titleText.text = "话题 ${header.topicId}"
            infoText.text = "${header.segmentCount} 条发言"
        }
    }

    class SegmentViewHolder(itemView: View, private val onSpeakerClick: ((TranscriptSegment) -> Unit)?) : RecyclerView.ViewHolder(itemView) {
        private val speakerText: TextView = itemView.findViewById(R.id.tv_speaker)
        private val contentText: TextView = itemView.findViewById(R.id.tv_content)
        private val timeText: TextView = itemView.findViewById(R.id.tv_time)

        fun bind(segment: TranscriptSegment, context: Context, highlightQuery: String? = null) {
            speakerText.visibility = View.VISIBLE
            timeText.visibility = View.VISIBLE

            speakerText.text = segment.displaySpeaker

            val colorIndex = (segment.displaySpeaker.hashCode() and Integer.MAX_VALUE) % SPEAKER_COLORS.size
            val color = ContextCompat.getColor(context, SPEAKER_COLORS[colorIndex])
            speakerText.setTextColor(color)

            speakerText.setOnClickListener { onSpeakerClick?.invoke(segment) }

            contentText.apply {
                if (!highlightQuery.isNullOrBlank()) {
                    val source = segment.text
                    val lower = source.lowercase()
                    val spannable = SpannableString(source)
                    var start = 0
                    while (start < lower.length) {
                        val idx = lower.indexOf(highlightQuery.lowercase(), start)
                        if (idx == -1) break
                        spannable.setSpan(
                            BackgroundColorSpan(0xFFFFEB3B.toInt()),
                            idx, idx + highlightQuery.length,
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        start = idx + highlightQuery.length
                    }
                    text = spannable
                } else {
                    text = segment.text
                }
                setTextColor(ContextCompat.getColor(context, R.color.on_background))
                setTypeface(null, Typeface.NORMAL)
            }

            timeText.text = segment.formattedTime
        }

        fun bindInterim(text: String, context: Context) {
            speakerText.visibility = View.GONE
            timeText.visibility = View.VISIBLE
            timeText.text = "..."

            contentText.text = text
            contentText.setTextColor(ContextCompat.getColor(context, R.color.interim_text))
            contentText.setTypeface(contentText.typeface, Typeface.ITALIC)
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<AdapterItem>() {
        override fun areItemsTheSame(old: AdapterItem, new: AdapterItem): Boolean {
            return when {
                old is AdapterItem.TopicHeader && new is AdapterItem.TopicHeader -> old.topicId == new.topicId
                old is AdapterItem.SegmentItem && new is AdapterItem.SegmentItem ->
                    old.segment.id == new.segment.id || old.segment.sentenceId == new.segment.sentenceId
                else -> false
            }
        }
        override fun areContentsTheSame(old: AdapterItem, new: AdapterItem): Boolean {
            return when {
                old is AdapterItem.TopicHeader && new is AdapterItem.TopicHeader ->
                    old.topicId == new.topicId && old.segmentCount == new.segmentCount
                old is AdapterItem.SegmentItem && new is AdapterItem.SegmentItem ->
                    old.segment.text == new.segment.text && old.segment.displaySpeaker == new.segment.displaySpeaker
                else -> false
            }
        }
    }
}
