package com.example.meetingtranscriber.util

/**
 * 文本格式化工具
 *
 * 用于后处理阶段的文本规整：
 * - 数字格式化
 * - 日期格式化
 * - 全角/半角统一
 */
object TextFormatter {

    /**
     * 格式化一段转写文本
     */
    fun format(text: String): String {
        var result = text
        result = normalizePunctuation(result)
        result = normalizeSpacing(result)
        return result
    }

    /** 标点符号统一为中文全角 */
    private fun normalizePunctuation(text: String): String {
        return text
            .replace(",", "，")
            .replace(";", "；")
            .replace(":", "：")
            .replace("!", "！")
            .replace("?", "？")
            // 但保留英文引号可能有的特殊用法
    }

    /** 去除多余空格 */
    private fun normalizeSpacing(text: String): String {
        return text
            .replace(Regex("\\s{2,}"), " ")
            .replace(Regex("\\s+([,.，。！？；：、])"), "$1")
            .trim()
    }

    /**
     * 简单的数字口语转数字
     * "一千二百三十四" → "1234"
     * MVP 阶段跳过，云端 ASR 已经有 ITN（逆文本正则化）能力
     */
    fun spokenNumberToDigits(text: String): String {
        // Phase 2 实现
        return text
    }
}
