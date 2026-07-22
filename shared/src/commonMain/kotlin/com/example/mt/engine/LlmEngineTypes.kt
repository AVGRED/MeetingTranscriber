package com.example.mt.engine

// ═══════════════════════════════════════════════════════════════
// LLM 引擎类型
// ═══════════════════════════════════════════════════════════════

enum class LlmEngineType(val displayName: String, val isCloud: Boolean) {
    DOUBAO_CLOUD("豆包 (火山方舟)", isCloud = true),
    DASHSCOPE_CLOUD("通义千问 (DashScope)", isCloud = true),
    DEEPSEEK_CLOUD("DeepSeek", isCloud = true),
    KIMI_CLOUD("Kimi (月之暗面)", isCloud = true),
    ZHIPU_CLOUD("智谱 GLM", isCloud = true),
    SILICONFLOW_CLOUD("硅基流动 (聚合)", isCloud = true)
}

// ═══════════════════════════════════════════════════════════════
// 纪要风格
// ═══════════════════════════════════════════════════════════════

enum class SummaryStyle(val label: String) {
    STANDARD("标准纪要"),
    BULLET("要点列表"),
    DECISION_FOCUSED("决策重点")
}
