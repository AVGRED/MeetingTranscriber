package com.example.meetingtranscriber.ui.meeting

/**
 * 内置规则兜底纪要生成器。
 *
 * 当所有云端 LLM 均不可用时（无网络 + 无 API Key）作为最终回退。
 * 云端路径优先由 SummaryUseCase → EngineRouter → DoubaoEngine/DashScopeEngine 覆盖。
 */
object MeetingSummaryGenerator {

    suspend fun generate(fullTranscript: String): String {
        if (fullTranscript.isBlank()) return "转写内容为空，无法生成纪要。"
        return buildSimpleSummary(fullTranscript)
    }

    private fun buildSimpleSummary(transcript: String): String {
        val lines = transcript.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return "暂无转写内容。"

        val totalLines = lines.size
        val speakers = lines.mapNotNull { line ->
            Regex("^(会议人\\d+)").find(line)?.groupValues?.get(1)
        }.distinct()

        val durationEstimate = (totalLines * 10).coerceAtLeast(60)

        return buildString {
            appendLine("【会议基本信息】")
            appendLine("参与人数: ${speakers.size} 人 (${speakers.joinToString("、")})")
            appendLine("发言次数: $totalLines 条")
            appendLine("估计时长: 约 ${durationEstimate / 60} 分钟")
            appendLine()
            appendLine("【前 5 条发言摘要】")
            lines.take(5).forEach { appendLine(it.trim()) }
            appendLine()
            appendLine("注: 此为自动生成的简单摘要。配置豆包或通义千问 API 可获取更完整的会议纪要。")
        }
    }
}
