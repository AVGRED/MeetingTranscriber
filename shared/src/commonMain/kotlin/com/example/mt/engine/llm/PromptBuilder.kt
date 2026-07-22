package com.example.mt.engine.llm

import com.example.mt.engine.SummaryStyle

/**
 * 共享的 Prompt 构建工具 — 供云端 LLM 引擎使用。
 */
object PromptBuilder {

    /** 共享的系统提示词 */
    const val SYSTEM_PROMPT =
        "你是一位专业的会议纪要助手。直接输出纪要正文，禁止输出任何开场白、客套话、结尾语、署名、免责声明。"

    private val OUTPUT_RULES = """
格式要求：
- 直接输出纪要内容，不要写"以下是会议纪要""根据转写内容"等开场白
- 不要写"以上就是本次会议""纪要完毕""如有遗漏请指正"等结尾语
- 不要署名或添加免责声明
- 用中文输出
    """.trimIndent()

    fun build(
        transcript: String,
        style: SummaryStyle,
        roleDescription: String = "你是一位专业的会议纪要助手，请根据以下会议转写内容，生成一份结构化的会议纪要。",
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

$OUTPUT_RULES

会议转写内容：
$transcript
        """.trimIndent()
    }
}
