package com.example.meetingtranscriber.data.model

/**
 * 会议信息（UI 层使用的数据模型）
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
    val tag: String? = null,
    // v8: 引擎追踪
    val asrEngineType: String? = null,
    val llmEngineType: String? = null,
    val dialectUsed: String? = null
) {
    companion object {
        // SimpleDateFormat 构造含 locale 规则表加载且非线程安全：ThreadLocal 复用，
        // 不再每次列表 bind 新建（历史列表快滚时每行两处 getter 各建一个）
        private val DATE_FMT = object : ThreadLocal<java.text.SimpleDateFormat>() {
            override fun initialValue() =
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        }
        private val DATETIME_FMT = object : ThreadLocal<java.text.SimpleDateFormat>() {
            override fun initialValue() =
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        }
    }

    val isOngoing: Boolean get() = endTime == null

    /** 简短日期字符串（用于列表展示） */
    val date: String
        get() = DATE_FMT.get()!!.format(java.util.Date(startTime))

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
        get() = DATETIME_FMT.get()!!.format(java.util.Date(startTime))
}
