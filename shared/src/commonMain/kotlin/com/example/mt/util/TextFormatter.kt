package com.example.mt.util

/**
 * 文本格式化工具。
 */
object TextFormatter {

    private val DIGITS = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val UNITS = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)
    private val SPOKEN_NUMBER_RE = Regex("[零一二三四五六七八九十百千万]+")

    fun format(text: String): String {
        var result = text
        result = normalizePunctuation(result)
        result = normalizeSpacing(result)
        return result
    }

    private fun normalizePunctuation(text: String): String {
        return text
            .replace(",", "，")
            .replace(";", "；")
            .replace(":", "：")
            .replace("!", "！")
            .replace("?", "？")
    }

    private fun normalizeSpacing(text: String): String {
        return text
            .replace(Regex("\\s{2,}"), " ")
            .replace(Regex("\\s+([,.，。！？；：、])"), "$1")
            .trim()
    }

    fun spokenNumberToDigits(text: String): String {
        if (text.isBlank()) return text
        return SPOKEN_NUMBER_RE.replace(text) { match ->
            val s = match.value
            if (s.length == 1) {
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
