package com.example.mt.engine

// ═══════════════════════════════════════════════════════════════
// ASR 引擎类型枚举
// ═══════════════════════════════════════════════════════════════

enum class AsrEngineType(
    val displayName: String,
    val isCloud: Boolean,
    /** 支持的方言列表 */
    val dialectSupport: List<String> = listOf("普通话", "四川话", "粤语")
) {
    TINGWU_CLOUD("通义听悟", isCloud = true),
    VOLCENGINE_CLOUD("豆包 ASR", isCloud = true, dialectSupport = listOf("普通话")),
    PARAFORMER_CLOUD("阿里 Paraformer", isCloud = true, dialectSupport = listOf("普通话", "粤语")),
    XFYUN_CLOUD("讯飞实时转写", isCloud = true, dialectSupport = listOf("普通话")),
    TENCENT_CLOUD("腾讯云识别", isCloud = true, dialectSupport = listOf("普通话")),
    BAIDU_CLOUD("百度识别", isCloud = true, dialectSupport = listOf("普通话"))
}

// ═══════════════════════════════════════════════════════════════
// 数据结构
// ═══════════════════════════════════════════════════════════════

/**
 * ASR 转写配置。
 */
data class AsrConfig(
    /** 源语言代码，如 "cn" */
    val language: String = "cn",
    /** 可选的热词/词汇表 ID */
    val vocabularyId: String? = null
)

/**
 * ASR 返回的一条完整句子。
 */
data class AsrSentence(
    val text: String,
    val sentenceId: Long,
    /** 离线引擎固定为 "speaker_0"，云端返回实际 speakerId */
    val speakerId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val isFinal: Boolean = true,
    /** 上一句因软/硬截断在语流中间收尾，本句是它的续段（UI 层可据此合并显示）。 */
    val isContinuation: Boolean = false
)
