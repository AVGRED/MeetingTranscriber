package com.example.meetingtranscriber.util

import com.example.meetingtranscriber.data.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicSegmenterTest {

    private fun segment(
        id: Long, startMs: Long, endMs: Long, text: String = "test", topicId: Int = 0
    ) = TranscriptSegment(
        id = id, meetingId = 1, speakerId = "sp1",
        displaySpeaker = "发言人", text = text,
        startTimeMs = startMs, endTimeMs = endMs,
        topicId = topicId
    )

    @Test
    fun `empty list returns empty map`() {
        assertTrue(TopicSegmenter.segmentByTimeGap(emptyList()).isEmpty())
    }

    @Test
    fun `single segment returns one topic`() {
        val result = TopicSegmenter.segmentByTimeGap(
            listOf(segment(1, 0, 5000))
        )
        assertEquals(1, result.size)
        assertEquals(1, result[1]?.size)
    }

    @Test
    fun `continuous speech stays in same topic`() {
        val segments = listOf(
            segment(1, 0, 5000),
            segment(2, 6000, 12000),
            segment(3, 13000, 18000)
        )
        val result = TopicSegmenter.segmentByTimeGap(segments)
        assertEquals(1, result.size)
        assertEquals(3, result[1]?.size)
    }

    @Test
    fun `silence gap creates new topic`() {
        val segments = listOf(
            segment(1, 0, 5000),
            segment(2, 6000, 12000),
            segment(3, 50000, 55000),  // 38s gap after previous
            segment(4, 56000, 60000)
        )
        val result = TopicSegmenter.segmentByTimeGap(segments)
        assertEquals(2, result.size)
        assertEquals(2, result[1]?.size)
        assertEquals(2, result[2]?.size)
    }

    @Test
    fun `gap exactly at threshold stays in same topic`() {
        val segments = listOf(
            segment(1, 0, 5000),
            segment(2, 35000, 40000)  // 30s gap (35000 - 5000 = 30000) — not beyond threshold
        )
        val result = TopicSegmenter.segmentByTimeGap(segments)
        assertEquals(1, result.size)  // 30s is NOT greater than 30s
    }

    @Test
    fun `unsorted segments are sorted before processing`() {
        val segments = listOf(
            segment(3, 50000, 55000),
            segment(1, 0, 5000),
            segment(2, 6000, 12000)
        )
        val result = TopicSegmenter.segmentByTimeGap(segments)
        assertEquals(2, result.size)
    }
}
