package com.example.meetingtranscriber.ui.meeting

import android.util.Log
import com.example.meetingtranscriber.BuildConfig
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
 * Phase 2: 优先使用火山方舟 / 豆包生成摘要，未配置时回退到 DashScope 或本地规则。
 */
@Deprecated(
    message = "使用 engine.llm.DoubaoEngine / DashScopeEngine / QwenEngine 替代",
    replaceWith = ReplaceWith("com.example.meetingtranscriber.engine.llm.QwenEngine")
)
object MeetingSummaryGenerator {

    private const val TAG = "SummaryGenerator"

    suspend fun generate(fullTranscript: String): String = withContext(Dispatchers.IO) {
        if (fullTranscript.isBlank()) return@withContext "转写内容为空，无法生成纪要。"

        // 若已配置火山方舟 API Key，优先使用豆包生成纪要。
        val arkApiKey = BuildConfig.ARK_API_KEY
        if (arkApiKey.isNotBlank()) {
            try {
                return@withContext callDoubaoForSummary(fullTranscript, arkApiKey)
            } catch (e: Exception) {
                Log.e(TAG, "豆包纪要生成失败，尝试备用摘要: ${e.message}")
            }
        }

        // 兼容旧配置：若仍配置 DashScope API Key，则继续可用。
        val dashScopeApiKey = BuildConfig.DASHSCOPE_API_KEY
        if (dashScopeApiKey.isNotBlank()) {
            try {
                return@withContext callQwenForSummary(fullTranscript, dashScopeApiKey)
            } catch (e: Exception) {
                Log.e(TAG, "DashScope 纪要生成失败，回退到简单摘要: ${e.message}")
            }
        }

        buildSimpleSummary(fullTranscript)
    }

    /**
     * 调用火山方舟推理端点生成会议纪要。
     *
     * 配置项（优先使用 ARK_ENDPOINT_ID，回退到 ARK_MODEL）：
     * - ARK_API_KEY: 火山方舟 API Key
     * - ARK_ENDPOINT_ID: 火山方舟推理端点 ID（如 ep-20250101123456-xxxxx）
     * - ARK_MODEL: （兼容旧配置）模型名或端点 ID，默认 doubao-seed-1-6-250615
     * - ARK_BASE_URL: 默认 https://ark.cn-beijing.volces.com/api/v3/chat/completions
     */
    private fun callDoubaoForSummary(transcript: String, apiKey: String): String {
        val prompt = buildSummaryPrompt(transcript)
        // ENDPOINT_ID 优先，回退到旧字段 MODEL，最后用默认值
        val endpointId = BuildConfig.ARK_ENDPOINT_ID.ifBlank {
            BuildConfig.ARK_MODEL.ifBlank { "doubao-seed-1-6-250615" }
        }
        val endpoint = BuildConfig.ARK_BASE_URL.ifBlank {
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        }

        val body = JSONObject().apply {
            put("model", endpointId)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一位专业的会议纪要助手，输出清晰、结构化、可执行。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_tokens", 1000)
            put("temperature", 0.3)
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: return "纪要生成失败"

        if (response.isSuccessful) {
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val text = choices
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?: ""
            return text.ifBlank { "纪要生成失败" }
        } else {
            val errorCode = try { JSONObject(responseBody).optJSONObject("error")?.optString("code") }
                catch (_: Exception) { null }
            val sanitized = errorCode ?: "HTTP ${response.code}"
            Log.e(TAG, "火山方舟错误: $sanitized")
            return "纪要生成失败"
        }
    }

    /**
     * 调用通义千问生成纪要（Phase 2 启用）
     * 注意：需要有效的 DashScope API Key，阿里云 RAM AccessKey 不适用于 DashScope。
     * 获取地址: https://dashscope.console.aliyun.com/apiKey
     */
    private fun callQwenForSummary(transcript: String, apiKey: String): String {
        val prompt = buildSummaryPrompt(transcript)

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
            val errorCode = try { JSONObject(responseBody).optString("code") }
                catch (_: Exception) { null }
            val sanitized = errorCode ?: "HTTP ${response.code}"
            Log.e(TAG, "DashScope 错误: $sanitized")
            return "纪要生成失败"
        }
    }

    private fun buildSummaryPrompt(transcript: String): String =
        com.example.meetingtranscriber.engine.llm.PromptBuilder.build(
            transcript, com.example.meetingtranscriber.engine.SummaryStyle.STANDARD
        )

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
            appendLine("注: 此为自动生成的简单摘要。配置豆包或通义千问 API 可获取更完整的会议纪要。")
        }
    }
}
