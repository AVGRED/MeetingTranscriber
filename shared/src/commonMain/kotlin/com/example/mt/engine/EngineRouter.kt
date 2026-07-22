package com.example.mt.engine

import com.example.mt.config.EngineKeys
import com.example.mt.engine.asr.CloudAsrProvider
import com.example.mt.platform.NetworkMonitor
import io.github.aakira.napier.Napier

/**
 * 引擎智能路由器（跨平台，无 Android 依赖）。
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
 * 网络不可用 / Key 缺失 → autoFallback ? 降级 : 报错
 * ```
 */
class EngineRouter(
    private val keys: EngineKeys,
    private val networkMonitor: NetworkMonitor = NetworkMonitor(),
    // 云端引擎由外部注入
    private var tingwuEngine: AsrEngine? = null,
    private var volcengineEngine: AsrEngine? = null,
    /** 通用云端 ASR（阿里 Paraformer/讯飞/腾讯云/百度），按类型索引 */
    private var cloudAsrEngines: Map<AsrEngineType, AsrEngine> = emptyMap(),
    private var doubaoEngine: LlmEngine? = null,
    private var dashScopeEngine: LlmEngine? = null,
    /** OpenAI 兼容云端 LLM（DeepSeek/Kimi/智谱/硅基流动等），按类型索引 */
    private var openAiCompatEngines: Map<LlmEngineType, LlmEngine> = emptyMap(),
) {

    // ── 公开属性：允许外部延迟注入 ──

    fun setCloudEngines(
        tingwu: AsrEngine? = null,
        volcengine: AsrEngine? = null,
        doubao: LlmEngine? = null,
        dashScope: LlmEngine? = null,
    ) {
        if (tingwu != null) tingwuEngine = tingwu
        if (volcengine != null) volcengineEngine = volcengine
        if (doubao != null) doubaoEngine = doubao
        if (dashScope != null) dashScopeEngine = dashScope
    }

    // ═══════════════════════════════════════════════════════════
    // ASR 引擎路由
    // ═══════════════════════════════════════════════════════════

    suspend fun resolveAsrEngine(overrideType: AsrEngineType? = null): AsrEngine {
        val preferred = overrideType ?: keys.preferredAsrEngine
        val hasNetwork = networkMonitor.isNetworkAvailable

        logRoute("ASR", preferred.displayName, hasNetwork)

        return when {
            !hasNetwork -> {
                throw NoEngineException("网络不可用，无法使用云端 ASR。请检查网络连接后重试。")
            }

            preferred == AsrEngineType.TINGWU_CLOUD -> {
                if (keys.hasTingwuKeys() && tingwuEngine != null) {
                    ensureInitializedOrThrow(tingwuEngine!!)
                    tingwuEngine!!
                } else if (keys.autoFallback && keys.hasVolcengineKeys() && volcengineEngine != null) {
                    Napier.w("通义听悟 Key 未配置 → 降级到豆包 ASR")
                    ensureInitializedOrThrow(volcengineEngine!!)
                    volcengineEngine!!
                } else {
                    throw NoEngineException("通义听悟密钥未配置。请在「API 配置」页面设置 AccessKey ID/Secret/AppKey。")
                }
            }

            preferred == AsrEngineType.VOLCENGINE_CLOUD -> {
                if (keys.hasVolcengineKeys() && volcengineEngine != null) {
                    ensureInitializedOrThrow(volcengineEngine!!)
                    volcengineEngine!!
                } else if (keys.autoFallback && keys.hasTingwuKeys() && tingwuEngine != null) {
                    Napier.w("豆包 ASR Key 未配置 → 降级到通义听悟")
                    ensureInitializedOrThrow(tingwuEngine!!)
                    tingwuEngine!!
                } else {
                    throw NoEngineException("豆包 ASR 密钥未配置。请在「API 配置」页面设置 API Key 或 Access Token。")
                }
            }

            cloudAsrEngines.containsKey(preferred) -> {
                val engine = cloudAsrEngines.getValue(preferred)
                val hasKeys = CloudAsrProvider.of(preferred)?.hasKeys(keys) == true
                if (hasKeys) {
                    ensureInitializedOrThrow(engine)
                    engine
                } else if (keys.autoFallback && keys.hasVolcengineKeys() && volcengineEngine != null) {
                    Napier.w("${preferred.displayName} 密钥未配置 → 降级到豆包 ASR")
                    ensureInitializedOrThrow(volcengineEngine!!)
                    volcengineEngine!!
                } else {
                    throw NoEngineException("${preferred.displayName} 密钥未配置。请在「API 配置」页面填写。")
                }
            }

            else -> throw NoEngineException("未知的 ASR 引擎类型: $preferred")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LLM 引擎路由
    // ═══════════════════════════════════════════════════════════

    suspend fun resolveLlmEngine(): LlmEngine {
        val preferred = keys.preferredLlmEngine
        val hasNetwork = networkMonitor.isNetworkAvailable

        logRoute("LLM", preferred.displayName, hasNetwork)

        if (!hasNetwork) {
            throw NoEngineException("网络不可用，无法使用云端 LLM 生成纪要")
        }

        return when {
            preferred == LlmEngineType.DOUBAO_CLOUD -> {
                if (keys.hasArkKey() && doubaoEngine != null) {
                    ensureInitializedOrThrow(doubaoEngine!!)
                    doubaoEngine!!
                } else {
                    throw NoEngineException("火山方舟 Key 未配置。请在「API 配置」页面设置 API Key 和端点 ID。")
                }
            }

            preferred == LlmEngineType.DASHSCOPE_CLOUD -> {
                if (keys.hasDashScopeKey() && dashScopeEngine != null) {
                    ensureInitializedOrThrow(dashScopeEngine!!)
                    dashScopeEngine!!
                } else {
                    throw NoEngineException("DashScope Key 未配置。请在「API 配置」页面设置 API Key。")
                }
            }

            openAiCompatEngines.containsKey(preferred) -> {
                val engine = openAiCompatEngines.getValue(preferred)
                if (keys.hasLlmKey(preferred)) {
                    ensureInitializedOrThrow(engine)
                    engine
                } else {
                    throw NoEngineException("${preferred.displayName} Key 未配置。请在「API 配置」页面设置。")
                }
            }

            else -> throw NoEngineException("未知的 LLM 引擎类型: $preferred")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private suspend fun ensureInitialized(engine: AsrEngine): Result<Unit> {
        val status = engine.engineStatus.value
        if (status.state == EngineState.IDLE || status.state == EngineState.ERROR) {
            Napier.d("正在初始化 ${engine.type.displayName}...")
            return engine.initialize().onFailure { e ->
                Napier.e("${engine.type.displayName} 初始化失败: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    private suspend fun ensureInitialized(engine: LlmEngine): Result<Unit> {
        val status = engine.engineStatus.value
        if (status.state == EngineState.IDLE || status.state == EngineState.ERROR) {
            Napier.d("正在初始化 ${engine.type.displayName}...")
            return engine.initialize().onFailure { e ->
                Napier.e("${engine.type.displayName} 初始化失败: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    private suspend fun ensureInitializedOrThrow(engine: AsrEngine) {
        ensureInitialized(engine).onFailure { e ->
            throw NoEngineException("${engine.type.displayName} 初始化失败: ${e.message}", e)
        }
    }

    private suspend fun ensureInitializedOrThrow(engine: LlmEngine) {
        ensureInitialized(engine).onFailure { e ->
            throw NoEngineException("${engine.type.displayName} 初始化失败: ${e.message}", e)
        }
    }

    private fun logRoute(kind: String, preferred: String, hasNetwork: Boolean) {
        Napier.i("🔄 [$kind] 路由: 偏好=$preferred | 网络=${if (hasNetwork) "可用" else "离线"}" +
                " | 自动降级=${if (keys.autoFallback) "开" else "关"}")
    }

    companion object {
        private const val TAG = "EngineRouter"
    }
}

/**
 * 没有可用的引擎时抛出。
 */
class NoEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
