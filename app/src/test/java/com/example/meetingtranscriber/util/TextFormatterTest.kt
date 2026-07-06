package com.example.meetingtranscriber.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFormatterTest {

    @Test
    fun `format normalizes punctuation`() {
        assertEquals("你好，世界！", TextFormatter.format("你好,世界!"))
        assertEquals("今天：晴；明天：雨", TextFormatter.format("今天:晴;明天:雨"))
    }

    @Test
    fun `format normalizes spacing`() {
        assertEquals("你好", TextFormatter.format("  你好  "))
        assertEquals("一，二，三", TextFormatter.format("一 ，二 ，三"))
    }

    @Test
    fun `spokenNumberToDigits handles single digits`() {
        assertEquals("价格是5元", TextFormatter.spokenNumberToDigits("价格是五元"))
        assertEquals("有3个人", TextFormatter.spokenNumberToDigits("有三个人"))
    }

    @Test
    fun `spokenNumberToDigits handles tens`() {
        assertEquals("20个", TextFormatter.spokenNumberToDigits("二十个"))
        assertEquals("15元", TextFormatter.spokenNumberToDigits("十五元"))
        assertEquals("99元", TextFormatter.spokenNumberToDigits("九十九元"))
    }

    @Test
    fun `spokenNumberToDigits handles hundreds`() {
        assertEquals("100元", TextFormatter.spokenNumberToDigits("一百元"))
        assertEquals("305元", TextFormatter.spokenNumberToDigits("三百零五元"))
        assertEquals("520元", TextFormatter.spokenNumberToDigits("五百二十元"))
        assertEquals("999个", TextFormatter.spokenNumberToDigits("九百九十九个"))
    }

    @Test
    fun `spokenNumberToDigits handles thousands`() {
        assertEquals("1000元", TextFormatter.spokenNumberToDigits("一千元"))
        assertEquals("1234人", TextFormatter.spokenNumberToDigits("一千二百三十四人"))
        assertEquals("2025年", TextFormatter.spokenNumberToDigits("二千零二十五年"))
    }

    @Test
    fun `spokenNumberToDigits handles large numbers`() {
        assertEquals("12000元", TextFormatter.spokenNumberToDigits("一万二千元"))
        assertEquals("50500人", TextFormatter.spokenNumberToDigits("五万零五百人"))
    }

    @Test
    fun `spokenNumberToDigits handles zero in middle`() {
        assertEquals("101个", TextFormatter.spokenNumberToDigits("一百零一个"))
        assertEquals("2005年", TextFormatter.spokenNumberToDigits("二千零五年"))
    }

    @Test
    fun `spokenNumberToDigits handles special cases`() {
        assertEquals("10元", TextFormatter.spokenNumberToDigits("十元"))
    }

    @Test
    fun `spokenNumberToDigits handles mixed content`() {
        val input = "Q2营收增长了15%，总计一千二百三十四元"
        val expected = "Q2营收增长了15%，总计1234元"
        assertEquals(expected, TextFormatter.spokenNumberToDigits(input))
    }

    @Test
    fun `spokenNumberToDigits blank input returns unchanged`() {
        assertEquals("", TextFormatter.spokenNumberToDigits(""))
        assertEquals("hello", TextFormatter.spokenNumberToDigits("hello"))
    }

    @Test
    fun `spokenNumberToDigits numbers only no change`() {
        assertEquals("12345", TextFormatter.spokenNumberToDigits("12345"))
        assertEquals("价格是500元", TextFormatter.spokenNumberToDigits("价格是500元"))
    }
}
