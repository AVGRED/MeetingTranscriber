package com.example.mt.data.model

/**
 * 一条转写片段：某个说话人说的一句话。
 */
data class TranscriptSegment(
    val id: Long = 0,
    val meetingId: Long = 0,
    val speakerId: String,
    val displaySpeaker: String,
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val sentenceId: Long = 0,
    val isInterim: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val topicId: Int = 0,
) {
    val formattedTime: String
        get() {
            val totalSeconds = startTimeMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
            else "%02d:%02d".format(minutes, seconds)
        }
}
