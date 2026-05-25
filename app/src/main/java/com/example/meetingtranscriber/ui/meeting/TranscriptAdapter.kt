package com.example.meetingtranscriber.ui.meeting

import android.content.Context
import android.graphics.Typeface
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

class TranscriptAdapter : ListAdapter<TranscriptSegment, TranscriptAdapter.ViewHolder>(DiffCallback) {

    companion object {
        private const val VIEW_TYPE_SEGMENT = 0
        private const val VIEW_TYPE_INTERIM = 1

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

    fun setInterimText(text: String?) {
        val hadInterim = interimText != null
        val hasInterim = !text.isNullOrBlank()
        interimText = if (hasInterim) text else null

        when {
            !hadInterim && hasInterim -> notifyItemInserted(itemCount)
            hadInterim && !hasInterim -> notifyItemRemoved(itemCount)
            hadInterim && hasInterim -> notifyItemChanged(itemCount - 1)
            else -> { /* none → none, nothing to do */ }
        }
    }

    override fun getItemCount(): Int {
        return super.getItemCount() + if (interimText != null) 1 else 0
    }

    override fun getItemViewType(position: Int): Int {
        return if (interimText != null && position == itemCount - 1) {
            VIEW_TYPE_INTERIM
        } else {
            VIEW_TYPE_SEGMENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transcript_segment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (getItemViewType(position) == VIEW_TYPE_INTERIM) {
            holder.bindInterim(interimText ?: "", holder.itemView.context)
        } else {
            val segment = getItem(position)
            holder.bind(segment, holder.itemView.context)
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val speakerText: TextView = itemView.findViewById(R.id.tv_speaker)
        private val contentText: TextView = itemView.findViewById(R.id.tv_content)
        private val timeText: TextView = itemView.findViewById(R.id.tv_time)

        fun bind(segment: TranscriptSegment, context: Context) {
            speakerText.visibility = View.VISIBLE
            timeText.visibility = View.VISIBLE

            speakerText.text = segment.displaySpeaker

            val colorIndex = (segment.displaySpeaker.hashCode() and Integer.MAX_VALUE) % SPEAKER_COLORS.size
            val color = ContextCompat.getColor(context, SPEAKER_COLORS[colorIndex])
            speakerText.setTextColor(color)

            contentText.text = segment.text
            contentText.setTextColor(ContextCompat.getColor(context, R.color.on_background))
            contentText.setTypeface(null, Typeface.NORMAL)

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

    object DiffCallback : DiffUtil.ItemCallback<TranscriptSegment>() {
        override fun areItemsTheSame(old: TranscriptSegment, new: TranscriptSegment): Boolean {
            return old.id == new.id || old.sentenceId == new.sentenceId
        }
        override fun areContentsTheSame(old: TranscriptSegment, new: TranscriptSegment): Boolean {
            return old.text == new.text && old.displaySpeaker == new.displaySpeaker
        }
    }
}
