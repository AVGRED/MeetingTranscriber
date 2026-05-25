package com.example.meetingtranscriber.data.model

/**
 * 一条转写片段：某个说话人说的一句话
 */
data class TranscriptSegment(
    val id: Long = 0,
    val meetingId: Long = 0,
    val speakerId: String,               // 云端返回的原始 speaker_id
    val displaySpeaker: String,          // 显示用的 "会议人1" / "会议人2"
    val text: String,                    // 转写文本内容
    val startTimeMs: Long,               // 这句话开始时间 (相对会议开始)
    val endTimeMs: Long,                 // 这句话结束时间
    val sentenceId: Long = 0,            // 云端句子 ID
    val isInterim: Boolean = false,      // 是否为中间结果
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 格式化的时间戳 */
    val formattedTime: String
        get() {
            val totalSeconds = startTimeMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%02d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
}
