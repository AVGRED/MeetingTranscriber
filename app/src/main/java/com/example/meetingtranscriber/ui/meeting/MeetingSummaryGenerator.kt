package com.example.meetingtranscriber.ui.meeting

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 会议纪要生成器
 *
 * MVP 阶段使用内置规则生成简单摘要。
 * Phase 2: 配置 DashScope API Key 后切换到通义千问 LLM 摘要。
 */
object MeetingSummaryGenerator {

    private const val TAG = "SummaryGenerator"

    suspend fun generate(fullTranscript: String): String = withContext(Dispatchers.IO) {
        if (fullTranscript.isBlank()) return@withContext "转写内容为空，无法生成纪要。"

        // Phase 2: 配置 DASHSCOPE_API_KEY 后，取消注释以下代码启用 LLM 摘要：
        // val apiKey = BuildConfig.DASHSCOPE_API_KEY
        // if (apiKey.isNotBlank()) {
        //     try {
        //         return@withContext callQwenForSummary(fullTranscript, apiKey)
        //     } catch (e: Exception) {
        //         Log.e(TAG, "LLM 纪要生成失败，回退到简单摘要: ${e.message}")
        //     }
        // }

        buildSimpleSummary(fullTranscript)
    }

    /**
     * 调用通义千问生成纪要（Phase 2 启用）
     * 注意：需要有效的 DashScope API Key，阿里云 RAM AccessKey 不适用于 DashScope。
     * 获取地址: https://dashscope.console.aliyun.com/apiKey
     */
    @Suppress("unused")
    private fun callQwenForSummary(transcript: String, apiKey: String): String {
        val prompt = """
你是一位专业的会议纪要助手。请根据以下会议转写内容，生成一份结构化的会议纪要。

要求：
1. 用一段话概述本次会议的主题和目的。
2. 列出 3-5 条主要讨论要点，每条用一句话概括。
3. 如果有明确的决议或待办事项，请单独列出。

会议转写内容：
$transcript
        """.trimIndent()

        val body = JSONObject().apply {
            put("model", "qwen-turbo")
            put("input", JSONObject().apply {
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            })
            put("parameters", JSONObject().apply {
                put("max_tokens", 1000)
                put("temperature", 0.3)
            })
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: return "纪要生成失败"

        if (response.isSuccessful) {
            val json = JSONObject(responseBody)
            val output = json.optJSONObject("output")
            val choices = output?.optJSONArray("choices")
            val text = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
            return text.ifBlank { "纪要生成失败" }
        } else {
            Log.e(TAG, "DashScope 错误: $responseBody")
            return "纪要生成失败"
        }
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
            appendLine("注: 此为自动生成的简单摘要。配置通义千问 API 可获取更完整的会议纪要。")
        }
    }
}
