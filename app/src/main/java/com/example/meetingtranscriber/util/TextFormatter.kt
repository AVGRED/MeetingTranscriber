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

    private val DIGITS = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private val UNITS = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)
    private val SPOKEN_NUMBER_RE = Regex("[零一二三四五六七八九十百千万]+")

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
     * 中文口语数字转阿拉伯数字
     * "一千二百三十四" → "1234" / "三百零五" → "305"
     * 仅处理整数，涵盖 0–9999 常用范围
     */
    fun spokenNumberToDigits(text: String): String {
        if (text.isBlank()) return text
        return SPOKEN_NUMBER_RE.replace(text) { match ->
            val s = match.value
            if (s.length == 1) {
                // 十 → 10, 百/千/万 单独出现保持原样
                if (s[0] == '十') "10" else DIGITS[s[0]]?.toString() ?: s
            } else {
                var result = 0
                var current = 0
                for (ch in s) {
                    when {
                        ch in DIGITS -> current = DIGITS[ch]!!
                        ch in UNITS -> {
                            val unit = UNITS[ch]!!
                            current = if (current == 0 && unit >= 10) 1 else current
                            if (unit >= 10000) {
                                result = (result + current) * unit
                                current = 0
                            } else {
                                result += current * unit
                                current = 0
                            }
                        }
                    }
                }
                (result + current).toString()
            }
        }
    }
}
