package com.example.meetingtranscriber.util

data class LanguageOption(val code: String, val displayName: String)

val SUPPORTED_LANGUAGES = listOf(
    LanguageOption("cn", "普通话"),
    LanguageOption("yue", "粤语"),
    LanguageOption("en", "英语"),
    LanguageOption("cn-en", "中英混合")
)
