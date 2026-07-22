package com.example.mt.engine.llm

import com.example.mt.config.EngineKeys
import com.example.mt.engine.*
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 豆包（火山方舟）云端 LLM 引擎。
 */
class DoubaoEngine(
    private val keys: EngineKeys,
) : LlmEngine {

    override val type: LlmEngineType = LlmEngineType.DOUBAO_CLOUD

    private val _generationProgress = MutableStateFlow(0f)
    override val generationProgress: StateFlow<Float> = _generationProgress

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeCall: Call? = null

    override suspend fun initialize(): Result<Unit> {
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return Result.success(Unit)
        }

        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在验证豆包 API Key...")

        if (keys.arkApiKey.isBlank()) {
            val msg = "火山方舟 API Key 未配置"
            Napier.w("$TAG: $msg")
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            return Result.failure(IllegalStateException(msg))
        }

        _engineStatus.value = EngineStatus(EngineState.READY, "豆包已就绪")
        Napier.i("$TAG: 豆包 LLM 引擎初始化成功")
        return Result.success(Unit)
    }

    override suspend fun generateSummary(transcript: String, style: SummaryStyle): Result<String> =
        withContext(Dispatchers.Default) {
            if (transcript.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("转写内容为空"))
            }
            if (_engineStatus.value.state != EngineState.READY) {
                return@withContext Result.failure(IllegalStateException("引擎未就绪"))
            }

            _engineStatus.value = EngineStatus(EngineState.RUNNING)
            _generationProgress.value = 0f

            try {
                val apiKey = keys.arkApiKey
                val endpointId = keys.arkEndpointId.ifBlank { DEFAULT_MODEL }
                val prompt = PromptBuilder.build(transcript, style)

                _generationProgress.value = 0.3f

                val body = JSONObject().apply {
                    put("model", endpointId)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", PromptBuilder.SYSTEM_PROMPT)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                    put("max_tokens", MAX_TOKENS)
                    put("temperature", 0.3)
                }

                val request = Request.Builder()
                    .url(API_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()

                _generationProgress.value = 0.5f

                val call = client.newCall(request)
                activeCall = call
                val response = call.execute()
                val responseBody = response.body?.string()

                _generationProgress.value = 0.8f

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val choices = json.optJSONArray("choices")
                    val text = choices?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")

                    _generationProgress.value = 1f
                    _engineStatus.value = EngineStatus(EngineState.READY, "纪要已生成")

                    if (text != null && text.isNotBlank()) {
                        Napier.i("$TAG: 豆包纪要生成成功 (${text.length} 字)")
                        Result.success(text)
                    } else {
                        Napier.w("$TAG: 豆包返回空内容")
                        Result.failure(IOException("豆包返回空内容"))
                    }
                } else {
                    val errorMsg = try {
                        responseBody?.let { JSONObject(it).optJSONObject("error")?.optString("code") }
                    } catch (_: Exception) { null } ?: "HTTP ${response.code}"

                    Napier.e("$TAG: 豆包 API 错误: $errorMsg")
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "API 错误: $errorMsg")
                    Result.failure(IOException(errorMsg))
                }
            } catch (e: IOException) {
                Napier.e("$TAG: 豆包网络错误: ${e.message}", e)
                _engineStatus.value = EngineStatus(EngineState.ERROR, "网络错误: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Napier.e("$TAG: 豆包异常: ${e.message}", e)
                _engineStatus.value = EngineStatus(EngineState.ERROR, "异常: ${e.message}")
                Result.failure(e)
            } finally {
                _generationProgress.value = 0f
            }
        }

    override fun cancel() {
        activeCall?.cancel()
        Napier.i("$TAG: 已请求取消生成")
    }

    override suspend fun dispose() {
        cancel()
        _generationProgress.value = 0f
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        activeCall = null
        Napier.i("$TAG: 豆包 LLM 引擎已释放")
    }

    companion object {
        private const val TAG = "DoubaoEngine"
        private const val API_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        private const val DEFAULT_MODEL = "doubao-seed-1-6-250615"
        private const val MAX_TOKENS = 1000

        val PRESET_MODELS = listOf(
            "doubao-seed-evolving",
            "doubao-seed-2.1-pro",
            "doubao-seed-2.1-turbo",
            "doubao-seed-1-6-250615",
        )

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
