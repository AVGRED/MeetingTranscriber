package com.example.meetingtranscriber.engine.llm

import com.example.meetingtranscriber.engine.SummaryStyle

/**
 * 共享的 Prompt 构建工具 — 供云端 LLM 引擎（DoubaoEngine / DashScopeEngine 等）使用。
 */
object PromptBuilder {

    /** 共享的系统提示词，供所有 LLM 引擎统一引用 */
    const val SYSTEM_PROMPT = "你是一位专业的会议纪要助手，输出清晰、结构化、可执行。"

    /**
     * 构建云端 LLM 的通用 prompt。
     *
     * @param transcript 完整转写文本
     * @param style 纪要风格
     * @param roleDescription 角色描述（可自定义，默认 "专业的会议纪要助手"）
     */
    fun build(
        transcript: String,
        style: SummaryStyle,
        roleDescription: String = "你是一位专业的会议纪要助手，请根据以下会议转写内容，生成一份结构化的会议纪要。"
    ): String {
        val styleInstruction = when (style) {
            SummaryStyle.STANDARD -> """
要求：
1. 用一段话概述本次会议的主题和目的。
2. 列出 3-5 条主要讨论要点，每条用一句话概括。
3. 如果有明确的决议或待办事项，请单独列出。
            """.trimIndent()

            SummaryStyle.BULLET -> """
要求：
1. 以简洁的要点列表形式呈现会议内容。
2. 每条要点用 "· " 开头，一句话说清。
3. 分为「主题」「讨论」「决议」三个小节。
4. 每小节 3-5 条要点。
            """.trimIndent()

            SummaryStyle.DECISION_FOCUSED -> """
要求：
1. 首先单独列出本次会议做出的所有决策（用编号列表）。
2. 每条决策附简要背景（1-2 句）。
3. 然后列出待办事项，每项标注负责人（如能从原文推断）和截止时间（如有）。
4. 最后附 2-3 句会议概述。
            """.trimIndent()
        }

        return """
$roleDescription

$styleInstruction

会议转写内容：
$transcript
        """.trimIndent()
    }
}
