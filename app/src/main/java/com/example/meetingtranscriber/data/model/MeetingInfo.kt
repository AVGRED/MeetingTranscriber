package com.example.meetingtranscriber.data.model

/**
 * 会议信息
 */
data class MeetingInfo(
    val id: Long = 0,
    val title: String,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val durationSeconds: Int = 0,
    val speakerCount: Int = 0,
    val segmentCount: Int = 0,
    val summary: String? = null,
    val isOffline: Boolean = false,
    val audioFilePath: String? = null,
    val isArchived: Boolean = false,
    val archivedAt: Long? = null,
    val tag: String? = null
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
