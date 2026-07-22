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
 * OpenAI Chat Completions 兼容厂家描述。
 */
enum class OpenAiCompatProvider(
    val type: LlmEngineType,
    val apiUrl: String,
    val defaultModel: String,
    val presetModels: List<String>,
    val keyUrl: String,
) {
    DEEPSEEK(
        type = LlmEngineType.DEEPSEEK_CLOUD,
        apiUrl = "https://api.deepseek.com/chat/completions",
        defaultModel = "deepseek-chat",
        presetModels = listOf("deepseek-chat", "deepseek-reasoner", "deepseek-v4-pro", "deepseek-v4-flash"),
        keyUrl = "https://platform.deepseek.com/api_keys",
    ),
    KIMI(
        type = LlmEngineType.KIMI_CLOUD,
        apiUrl = "https://api.moonshot.cn/v1/chat/completions",
        defaultModel = "kimi-latest",
        presetModels = listOf("kimi-latest", "kimi-k2.6", "kimi-k2.7-code", "kimi-k2-thinking", "moonshot-v1-32k"),
        keyUrl = "https://platform.moonshot.cn/console/api-keys",
    ),
    ZHIPU(
        type = LlmEngineType.ZHIPU_CLOUD,
        apiUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
        defaultModel = "glm-4.7-flash",
        presetModels = listOf("glm-4.7-flash", "glm-5-turbo", "glm-5.2", "glm-4.5-air"),
        keyUrl = "https://bigmodel.cn/usercenter/proj-mgmt/apikeys",
    ),
    SILICONFLOW(
        type = LlmEngineType.SILICONFLOW_CLOUD,
        apiUrl = "https://api.siliconflow.cn/v1/chat/completions",
        defaultModel = "deepseek-ai/DeepSeek-V3",
        presetModels = listOf(
            "deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1",
            "Qwen/Qwen3.5-397B-A17B", "Qwen/Qwen3-235B-A22B-Instruct-2507",
            "MiniMaxAI/MiniMax-M2.1",
        ),
        keyUrl = "https://cloud.siliconflow.cn/account/ak",
    );

    companion object {
        fun of(type: LlmEngineType): OpenAiCompatProvider? = entries.find { it.type == type }
    }
}

/**
 * OpenAI Chat Completions 兼容云端 LLM 引擎（按厂家参数化）。
 */
class OpenAiCompatEngine(
    private val keys: EngineKeys,
    private val provider: OpenAiCompatProvider,
) : LlmEngine {

    override val type: LlmEngineType = provider.type

    private val _generationProgress = MutableStateFlow(0f)
    override val generationProgress: StateFlow<Float> = _generationProgress

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeCall: Call? = null

    private val label get() = type.displayName

    override suspend fun initialize(): Result<Unit> {
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return Result.success(Unit)
        }

        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在验证 $label API Key...")

        if (!keys.hasLlmKey(type)) {
            val msg = "$label API Key 未配置"
            Napier.w("$TAG: $msg")
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            return Result.failure(IllegalStateException(msg))
        }

        _engineStatus.value = EngineStatus(EngineState.READY, "$label 已就绪")
        Napier.i("$TAG: $label LLM 引擎初始化成功")
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
                val apiKey = keys.getLlmApiKey(type)
                val model = keys.getLlmModel(type).ifBlank { provider.defaultModel }
                val prompt = PromptBuilder.build(transcript, style)

                _generationProgress.value = 0.3f

                val body = JSONObject().apply {
                    put("model", model)
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
                    .url(provider.apiUrl)
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
                        Napier.i("$TAG: $label ($model) 纪要生成成功 (${text.length} 字)")
                        Result.success(text)
                    } else {
                        Napier.w("$TAG: $label 返回空内容")
                        Result.failure(IOException("$label 返回空内容"))
                    }
                } else {
                    val errorMsg = try {
                        responseBody?.let { JSONObject(it).optJSONObject("error")?.optString("message") }
                            ?.takeIf { it.isNotBlank() }
                    } catch (_: Exception) { null } ?: "HTTP ${response.code}"

                    Napier.e("$TAG: $label API 错误: $errorMsg")
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "API 错误: $errorMsg")
                    Result.failure(IOException(errorMsg))
                }
            } catch (e: IOException) {
                Napier.e("$TAG: $label 网络错误: ${e.message}", e)
                _engineStatus.value = EngineStatus(EngineState.ERROR, "网络错误: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Napier.e("$TAG: $label 异常: ${e.message}", e)
                _engineStatus.value = EngineStatus(EngineState.ERROR, "异常: ${e.message}")
                Result.failure(e)
            } finally {
                if (_engineStatus.value.state == EngineState.RUNNING) {
                    _generationProgress.value = 0f
                }
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
        Napier.i("$TAG: $label LLM 引擎已释放")
    }

    companion object {
        private const val TAG = "OpenAiCompatEngine"
        private const val MAX_TOKENS = 1000
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
