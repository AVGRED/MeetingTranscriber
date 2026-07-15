package com.example.meetingtranscriber.engine.llm

import android.content.Context
import android.util.Log
import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.engine.*
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
 *
 * DeepSeek / Kimi / 智谱 / 硅基流动等国产大模型 API 均兼容该协议，
 * 一个 [OpenAiCompatEngine] 按厂家参数化即可全部覆盖。
 * 型号 ID 预置列表核实于 2026-07（各厂官方文档），下拉可手动输入新型号。
 */
enum class OpenAiCompatProvider(
    val type: LlmEngineType,
    val apiUrl: String,
    val defaultModel: String,
    val presetModels: List<String>,
    /** API Key 获取地址（接入说明用） */
    val keyUrl: String
) {
    DEEPSEEK(
        type = LlmEngineType.DEEPSEEK_CLOUD,
        apiUrl = "https://api.deepseek.com/chat/completions",
        defaultModel = "deepseek-chat",
        presetModels = listOf(
            "deepseek-chat",        // 始终指向最新对话旗舰
            "deepseek-reasoner",    // 推理模型
            "deepseek-v4-pro",
            "deepseek-v4-flash"
        ),
        keyUrl = "https://platform.deepseek.com/api_keys"
    ),
    KIMI(
        type = LlmEngineType.KIMI_CLOUD,
        apiUrl = "https://api.moonshot.cn/v1/chat/completions",
        defaultModel = "kimi-latest",
        presetModels = listOf(
            "kimi-latest",          // 始终指向最新版本
            "kimi-k2.6",
            "kimi-k2.7-code",
            "kimi-k2-thinking",
            "moonshot-v1-32k"
        ),
        keyUrl = "https://platform.moonshot.cn/console/api-keys"
    ),
    ZHIPU(
        type = LlmEngineType.ZHIPU_CLOUD,
        apiUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
        defaultModel = "glm-4.7-flash",
        presetModels = listOf(
            "glm-4.7-flash",        // 免费型号
            "glm-5-turbo",
            "glm-5.2",
            "glm-4.5-air"
        ),
        keyUrl = "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"
    ),
    SILICONFLOW(
        type = LlmEngineType.SILICONFLOW_CLOUD,
        apiUrl = "https://api.siliconflow.cn/v1/chat/completions",
        defaultModel = "deepseek-ai/DeepSeek-V3",
        presetModels = listOf(
            "deepseek-ai/DeepSeek-V3",
            "deepseek-ai/DeepSeek-R1",
            "Qwen/Qwen3.5-397B-A17B",
            "Qwen/Qwen3-235B-A22B-Instruct-2507",
            "MiniMaxAI/MiniMax-M2.1"
        ),
        keyUrl = "https://cloud.siliconflow.cn/account/ak"
    );

    companion object {
        fun of(type: LlmEngineType): OpenAiCompatProvider? = entries.find { it.type == type }
    }
}

/**
 * OpenAI Chat Completions 兼容云端 LLM 引擎（按厂家参数化）。
 *
 * 密钥/型号来源: [PreferencesManager] — getLlmApiKey / getLlmModel（按引擎类型区分）。
 * 请求/响应格式与 [DoubaoEngine] 一致（choices[0].message.content）。
 */
class OpenAiCompatEngine(
    private val prefs: PreferencesManager,
    private val provider: OpenAiCompatProvider
) : LlmEngine {

    override val type: LlmEngineType = provider.type

    private val _generationProgress = MutableStateFlow(0f)
    override val generationProgress: StateFlow<Float> = _generationProgress

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)  // LLM 生成可能较慢
        .build()

    @Volatile private var activeCall: Call? = null

    private val label get() = type.displayName

    override suspend fun initialize(context: Context): Result<Unit> {
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return Result.success(Unit)
        }

        _engineStatus.value = EngineStatus(EngineState.LOADING, "正在验证 $label API Key...")

        if (!prefs.hasLlmKey(type)) {
            val msg = "$label API Key 未配置"
            Log.w(TAG, msg)
            _engineStatus.value = EngineStatus(EngineState.ERROR, msg)
            return Result.failure(IllegalStateException(msg))
        }

        _engineStatus.value = EngineStatus(EngineState.READY, "$label 已就绪")
        Log.i(TAG, "$label LLM 引擎初始化成功")
        return Result.success(Unit)
    }

    override suspend fun generateSummary(transcript: String, style: SummaryStyle): Result<String> =
        withContext(Dispatchers.IO) {
            if (transcript.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("转写内容为空"))
            }
            if (_engineStatus.value.state != EngineState.READY) {
                return@withContext Result.failure(IllegalStateException("引擎未就绪，当前状态: ${_engineStatus.value.state}"))
            }

            _engineStatus.value = EngineStatus(EngineState.RUNNING)
            _generationProgress.value = 0f

            try {
                val apiKey = prefs.getLlmApiKey(type)
                val model = prefs.getLlmModel(type).ifBlank { provider.defaultModel }
                val prompt = PromptBuilder.build(transcript, style)

                _generationProgress.value = 0.3f

                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", SYSTEM_PROMPT)
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

                activeCall = client.newCall(request)
                val response = activeCall!!.execute()
                val responseBody = response.body?.string()

                _generationProgress.value = 0.8f

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val choices = json.optJSONArray("choices")
                    val text = choices
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")

                    _generationProgress.value = 1f
                    _engineStatus.value = EngineStatus(EngineState.READY, "纪要已生成")

                    if (text != null && text.isNotBlank()) {
                        Log.i(TAG, "$label ($model) 纪要生成成功 (${text.length} 字)")
                        Result.success(text)
                    } else {
                        Log.w(TAG, "$label 返回空内容")
                        Result.failure(IOException("$label 返回空内容"))
                    }
                } else {
                    val errorMsg = try {
                        responseBody?.let { JSONObject(it).optJSONObject("error")?.optString("message") }
                            ?.takeIf { it.isNotBlank() }
                    } catch (_: Exception) { null } ?: "HTTP ${response.code}"

                    Log.e(TAG, "$label API 错误: $errorMsg")
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "API 错误: $errorMsg")
                    Result.failure(IOException(errorMsg))
                }
            } catch (e: IOException) {
                Log.e(TAG, "$label 网络错误: ${e.message}", e)
                _engineStatus.value = EngineStatus(EngineState.ERROR, "网络错误: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "$label 异常: ${e.message}", e)
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
        Log.i(TAG, "已请求取消生成")
    }

    override suspend fun dispose() {
        cancel()
        _generationProgress.value = 0f
        _engineStatus.value = EngineStatus(EngineState.IDLE)
        activeCall = null
        Log.i(TAG, "$label LLM 引擎已释放")
    }

    companion object {
        private const val TAG = "OpenAiCompatEngine"
        private const val MAX_TOKENS = 1000
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val SYSTEM_PROMPT = "你是一位专业的会议纪要助手，输出清晰、结构化、可执行。"
    }
}
