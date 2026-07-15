package com.example.meetingtranscriber.engine

import android.content.Context
import android.util.Log
import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.engine.asr.FunAsrCloudEngine
import com.example.meetingtranscriber.engine.asr.FunAsrEngine
import com.example.meetingtranscriber.network.NetworkMonitor
import kotlinx.coroutines.flow.StateFlow

/**
 * 引擎智能路由器。
 *
 * 根据以下条件自动选择 ASR / LLM 引擎：
 * 1. 用户偏好（设置中选择的引擎）
 * 2. 网络可用性
 * 3. API Key 是否已配置
 * 4. 自动降级开关
 *
 * 路由优先级：
 * ```
 * 网络可用 + Key 已配 + 用户选云端 → 云端引擎
 * 网络不可用 / Key 缺失 / 用户选本地 → 本地引擎
 * 云端异常 → autoFallback ? 本地引擎 : 报错
 * ```
 */
class EngineRouter(
    private val prefs: PreferencesManager,
    private val funAsrEngine: FunAsrEngine,
    // 云端引擎由外部注入（参见 Application 初始化）
    private var funAsrCloudEngine: FunAsrCloudEngine? = null,
    private var tingwuEngine: AsrEngine? = null,
    private var volcengineEngine: AsrEngine? = null,
    private var qwenEngine: LlmEngine? = null,
    private var doubaoEngine: LlmEngine? = null,
    private var dashScopeEngine: LlmEngine? = null,
    /** OpenAI 兼容云端 LLM（DeepSeek/Kimi/智谱/硅基流动等），按类型索引 */
    private var openAiCompatEngines: Map<LlmEngineType, LlmEngine> = emptyMap()
) {

    // ── 公开属性：允许外部延迟注入 ──

    fun setCloudEngines(
        funAsrCloud: FunAsrCloudEngine? = null,
        tingwu: AsrEngine? = null,
        volcengine: AsrEngine? = null,
        qwen: LlmEngine? = null,
        doubao: LlmEngine? = null,
        dashScope: LlmEngine? = null
    ) {
        if (funAsrCloud != null) funAsrCloudEngine = funAsrCloud
        if (tingwu != null) tingwuEngine = tingwu
        if (volcengine != null) volcengineEngine = volcengine
        if (qwen != null) qwenEngine = qwen
        if (doubao != null) doubaoEngine = doubao
        if (dashScope != null) dashScopeEngine = dashScope
    }

    // ═══════════════════════════════════════════════════════════
    // ASR 引擎路由
    // ═══════════════════════════════════════════════════════════

    /**
     * 解析当前可用的最佳 ASR 引擎。
     * 确保返回的引擎已经 initialize() 过。
     */
    suspend fun resolveAsrEngine(
        context: Context,
        overrideType: AsrEngineType? = null
    ): AsrEngine {
        val preferred = overrideType ?: prefs.preferredAsrEngine
        val hasNetwork = NetworkMonitor.isNetworkAvailable

        logRoute("ASR", preferred.displayName, hasNetwork)

        return when {
            // ── 本地引擎：sherpa-onnx SenseVoiceSmall ──
            preferred == AsrEngineType.FUNASR_LOCAL -> {
                ensureInitializedOrThrow(context, funAsrEngine)
                funAsrEngine
            }

            // ── 无网络 → 自动降级本地引擎 ──
            !hasNetwork -> {
                Log.i(TAG, "🌐 无网络，自动降级到本地 FunASR")
                ensureInitializedOrThrow(context, funAsrEngine)
                funAsrEngine
            }

            // ── FunASR 云端 ──
            preferred == AsrEngineType.FUNASR_CLOUD -> {
                if (prefs.hasFunAsrCloudUrl() && funAsrCloudEngine != null) {
                    ensureInitializedOrThrow(context, funAsrCloudEngine!!)
                    funAsrCloudEngine!!
                } else if (prefs.autoFallback && prefs.hasTingwuKeys() && tingwuEngine != null) {
                    Log.w(TAG, "FunASR 云端地址未配置 → 降级到通义听悟")
                    ensureInitializedOrThrow(context, tingwuEngine!!)
                    tingwuEngine!!
                } else if (prefs.autoFallback && prefs.hasVolcengineKeys() && volcengineEngine != null) {
                    Log.w(TAG, "FunASR 云端地址未配置 → 降级到豆包 ASR")
                    ensureInitializedOrThrow(context, volcengineEngine!!)
                    volcengineEngine!!
                } else if (prefs.autoFallback) {
                    Log.w(TAG, "无可用云端引擎 → 降级到本地 FunASR")
                    ensureInitializedOrThrow(context, funAsrEngine)
                    funAsrEngine
                } else {
                    throw NoEngineException(
                        "FunASR 云端地址未配置。请在「API 配置」页面设置 WebSocket URL，" +
                        "或切换到通义听悟/豆包 ASR 并配置相应密钥。")
                }
            }

            // ── 通义听悟 ──
            preferred == AsrEngineType.TINGWU_CLOUD -> {
                if (prefs.hasTingwuKeys() && tingwuEngine != null) {
                    ensureInitializedOrThrow(context, tingwuEngine!!)
                    tingwuEngine!!
                } else if (prefs.autoFallback && prefs.hasFunAsrCloudUrl() && funAsrCloudEngine != null) {
                    Log.w(TAG, "通义听悟 Key 未配置 → 降级到 FunASR 云端")
                    ensureInitializedOrThrow(context, funAsrCloudEngine!!)
                    funAsrCloudEngine!!
                } else if (prefs.autoFallback) {
                    Log.w(TAG, "无可用云端引擎 → 降级到本地 FunASR")
                    ensureInitializedOrThrow(context, funAsrEngine)
                    funAsrEngine
                } else {
                    throw NoEngineException(
                        "通义听悟密钥未配置。请在「API 配置」页面设置 AccessKey ID/Secret/AppKey。")
                }
            }

            // ── 豆包 ASR ──
            preferred == AsrEngineType.VOLCENGINE_CLOUD -> {
                if (prefs.hasVolcengineKeys() && volcengineEngine != null) {
                    ensureInitializedOrThrow(context, volcengineEngine!!)
                    volcengineEngine!!
                } else if (prefs.autoFallback && prefs.hasFunAsrCloudUrl() && funAsrCloudEngine != null) {
                    Log.w(TAG, "豆包 ASR Key 未配置 → 降级到 FunASR 云端")
                    ensureInitializedOrThrow(context, funAsrCloudEngine!!)
                    funAsrCloudEngine!!
                } else if (prefs.autoFallback) {
                    Log.w(TAG, "无可用云端引擎 → 降级到本地 FunASR")
                    ensureInitializedOrThrow(context, funAsrEngine)
                    funAsrEngine
                } else {
                    throw NoEngineException(
                        "豆包 ASR 密钥未配置。请在「API 配置」页面设置 API Key 或 Access Token。")
                }
            }

            else -> throw NoEngineException("未知的 ASR 引擎类型: $preferred")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LLM 引擎路由
    // ═══════════════════════════════════════════════════════════

    /**
     * 解析当前可用的最佳 LLM 引擎。
     * 云端优先（更高的生成质量），不可用时降级本地 Qwen。
     */
    suspend fun resolveLlmEngine(context: Context): LlmEngine {
        val preferred = prefs.preferredLlmEngine
        val hasNetwork = NetworkMonitor.isNetworkAvailable

        logRoute("LLM", preferred.displayName, hasNetwork)

        return when {
            // ── 用户强制离线，或根本没网 ──
            preferred == LlmEngineType.QWEN_LOCAL || !hasNetwork -> {
                if (!hasNetwork && preferred != LlmEngineType.QWEN_LOCAL) {
                    Log.i(TAG, "🌐 无网络，降级到 Qwen 本地引擎")
                }
                if (qwenEngine == null) throw NoEngineException("Qwen 本地引擎未配置")
                ensureInitializedOrThrow(context, qwenEngine!!)
                qwenEngine!!
            }

            // ── 豆包 ──
            preferred == LlmEngineType.DOUBAO_CLOUD -> {
                if (prefs.hasArkKey() && doubaoEngine != null) {
                    ensureOrFallback(context, doubaoEngine!!, qwenEngine!!, "豆包")
                } else if (prefs.autoFallback) {
                    Log.w(TAG, "火山方舟 Key 未配置 → 降级到 Qwen")
                    if (qwenEngine == null) throw NoEngineException("Qwen 本地引擎未配置，且云端不可用")
                    ensureInitializedOrThrow(context, qwenEngine!!)
                    qwenEngine!!
                } else {
                    throw NoEngineException("火山方舟 Key 未配置，且自动降级已关闭")
                }
            }

            // ── 通义千问 ──
            preferred == LlmEngineType.DASHSCOPE_CLOUD -> {
                if (prefs.hasDashScopeKey() && dashScopeEngine != null) {
                    ensureOrFallback(context, dashScopeEngine!!, qwenEngine!!, "DashScope")
                } else if (prefs.autoFallback) {
                    Log.w(TAG, "DashScope Key 未配置 → 降级到 Qwen")
                    if (qwenEngine == null) throw NoEngineException("Qwen 本地引擎未配置，且云端不可用")
                    ensureInitializedOrThrow(context, qwenEngine!!)
                    qwenEngine!!
                } else {
                    throw NoEngineException("DashScope Key 未配置，且自动降级已关闭")
                }
            }

            // ── OpenAI 兼容云端（DeepSeek/Kimi/智谱/硅基流动） ──
            openAiCompatEngines.containsKey(preferred) -> {
                val engine = openAiCompatEngines.getValue(preferred)
                if (prefs.hasLlmKey(preferred)) {
                    ensureOrFallback(context, engine, qwenEngine!!, preferred.displayName)
                } else if (prefs.autoFallback) {
                    Log.w(TAG, "${preferred.displayName} Key 未配置 → 降级到 Qwen")
                    if (qwenEngine == null) throw NoEngineException("Qwen 本地引擎未配置，且云端不可用")
                    ensureInitializedOrThrow(context, qwenEngine!!)
                    qwenEngine!!
                } else {
                    throw NoEngineException("${preferred.displayName} Key 未配置，且自动降级已关闭")
                }
            }

            else -> throw NoEngineException("未知的 LLM 引擎类型: $preferred")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private suspend fun ensureInitialized(context: Context, engine: AsrEngine): Result<Unit> {
        val status = engine.engineStatus.value
        if (status.state == EngineState.IDLE || status.state == EngineState.ERROR) {
            Log.d(TAG, "正在初始化 ${engine.type.displayName}...")
            return engine.initialize(context).onFailure { e ->
                Log.e(TAG, "${engine.type.displayName} 初始化失败: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    private suspend fun ensureInitialized(context: Context, engine: LlmEngine): Result<Unit> {
        val status = engine.engineStatus.value
        if (status.state == EngineState.IDLE || status.state == EngineState.ERROR) {
            Log.d(TAG, "正在初始化 ${engine.type.displayName}...")
            return engine.initialize(context).onFailure { e ->
                Log.e(TAG, "${engine.type.displayName} 初始化失败: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    /** 初始化引擎，失败则抛异常（用于本地引擎——没有退路）。 */
    private suspend fun ensureInitializedOrThrow(context: Context, engine: AsrEngine) {
        ensureInitialized(context, engine).onFailure { e ->
            throw NoEngineException("${engine.type.displayName} 初始化失败: ${e.message}", e)
        }
    }

    private suspend fun ensureInitializedOrThrow(context: Context, engine: LlmEngine) {
        ensureInitialized(context, engine).onFailure { e ->
            throw NoEngineException("${engine.type.displayName} 初始化失败: ${e.message}", e)
        }
    }

    /**
     * 尝试初始化首选引擎，失败时若用户开启自动降级则回退到本地引擎。
     */
    private suspend fun ensureOrFallback(
        context: Context,
        primary: AsrEngine,
        fallback: AsrEngine,
        label: String
    ): AsrEngine {
        if (ensureInitialized(context, primary).isSuccess) return primary
        if (!prefs.autoFallback) {
            throw NoEngineException("${label} 初始化失败，且自动降级已关闭")
        }
        Log.w(TAG, "$label 初始化失败 → 降级到 ${fallback.type.displayName}")
        ensureInitializedOrThrow(context, fallback)
        return fallback
    }

    private suspend fun ensureOrFallback(
        context: Context,
        primary: LlmEngine,
        fallback: LlmEngine,
        label: String
    ): LlmEngine {
        if (ensureInitialized(context, primary).isSuccess) return primary
        if (!prefs.autoFallback) {
            throw NoEngineException("${label} 初始化失败，且自动降级已关闭")
        }
        Log.w(TAG, "$label 初始化失败 → 降级到 ${fallback.type.displayName}")
        ensureInitializedOrThrow(context, fallback)
        return fallback
    }

    private fun logRoute(kind: String, preferred: String, hasNetwork: Boolean) {
        Log.i(TAG, "🔄 [$kind] 路由: 偏好=$preferred | 网络=${if (hasNetwork) "可用" else "离线"}" +
                " | 自动降级=${if (prefs.autoFallback) "开" else "关"}")
    }

    companion object {
        private const val TAG = "EngineRouter"
    }
}

/**
 * 没有可用的引擎时抛出。
 */
class NoEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
