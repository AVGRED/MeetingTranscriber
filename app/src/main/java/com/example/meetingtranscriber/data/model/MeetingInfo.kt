package com.example.meetingtranscriber.data.model

/**
 * 会议信息
 */
data class MeetingInfo(
    val id: Long = 0,
    val title: String,                      // 会议标题
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,              // null = 进行中
    val durationSeconds: Int = 0,
    val speakerCount: Int = 0,             // 说话人数量
    val segmentCount: Int = 0,             // 转写片段数
    val summary: String? = null            // 会议纪要
) {
    val isOngoing: Boolean get() = endTime == null

    val formattedDuration: String
        get() {
            val d = endTime?.let {
                ((it - startTime) / 1000).toInt()
            } ?: if (isOngoing) {
                ((System.currentTimeMillis() - startTime) / 1000).toInt()
            } else {
                durationSeconds
            }
            val h = d / 3600
            val m = (d % 3600) / 60
            val s = d % 60
            return if (h > 0) "%d时%02d分%02d秒".format(h, m, s)
            else "%d分%02d秒".format(m, s)
        }

    val formattedStartTime: String
        get() {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(startTime))
        }
}
