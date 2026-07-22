package com.example.mt.util

import com.example.mt.data.model.TranscriptSegment

object TopicSegmenter {
    private const val SILENCE_THRESHOLD_MS = 30_000L

    fun segmentByTimeGap(segments: List<TranscriptSegment>): Map<Int, List<TranscriptSegment>> {
        if (segments.isEmpty()) return emptyMap()
        val sorted = segments.sortedBy { it.startTimeMs }
        val topicMap = mutableMapOf<Int, MutableList<TranscriptSegment>>()
        var topicId = 1
        var lastEndMs = sorted.first().startTimeMs

        for (seg in sorted) {
            if (seg.startTimeMs - lastEndMs > SILENCE_THRESHOLD_MS) topicId++
            topicMap.getOrPut(topicId) { mutableListOf() }.add(seg)
            lastEndMs = seg.endTimeMs
        }
        return topicMap
    }
}
