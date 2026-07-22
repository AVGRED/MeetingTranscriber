package com.example.mt.config

import com.example.mt.engine.AsrEngineType
import com.example.mt.engine.LlmEngineType
import com.example.mt.engine.SummaryStyle

/**
 * 引擎密钥与偏好配置（纯数据类，通过构造函数注入）。
 *
 * 替代原 Android 项目中的 PreferencesManager + BuildConfig 密钥方案。
 * 各平台的 actual 实现负责从 KeyValueStore / 命令行 / 环境变量等
 * 来源组装此模型后注入到 EngineRouter 和各个引擎中。
 */
data class EngineKeys(
    // ── 通义听悟 ASR ──
    val tingwuAccessKeyId: String = "",
    val tingwuAccessKeySecret: String = "",
    val tingwuAppKey: String = "",

    // ── 豆包 (火山引擎) ASR ──
    val volcengineAsrApiKey: String = "",
    val volcengineAsrAccessToken: String = "",

    // ── 豆包 / 火山方舟 LLM ──
    val arkApiKey: String = "",
    val arkEndpointId: String = "",

    // ── DashScope / 通义千问 LLM ──
    val dashScopeApiKey: String = "",

    // ── 通用云端 ASR 凭证（按引擎类型 + 槽位 0-2 区分） ──
    val asrCredentials: Map<AsrEngineType, List<String>> = emptyMap(),

    // ── 通用云端 LLM 密钥/型号 ──
    val llmApiKeys: Map<LlmEngineType, String> = emptyMap(),
    val llmModels: Map<LlmEngineType, String> = emptyMap(),

    // ── 引擎偏好 ──
    val preferredAsrEngine: AsrEngineType = AsrEngineType.VOLCENGINE_CLOUD,
    val preferredLlmEngine: LlmEngineType = LlmEngineType.DOUBAO_CLOUD,
    val autoFallback: Boolean = true,
    val summaryStyle: SummaryStyle = SummaryStyle.STANDARD,
    val backgroundSilent: Boolean = false,
) {
    // ── 批量查询 ──

    fun hasTingwuKeys(): Boolean =
        tingwuAccessKeyId.isNotBlank() && tingwuAccessKeySecret.isNotBlank() && tingwuAppKey.isNotBlank()

    fun hasVolcengineKeys(): Boolean =
        volcengineAsrApiKey.isNotBlank() || volcengineAsrAccessToken.isNotBlank()

    fun hasArkKey(): Boolean = arkApiKey.isNotBlank()

    fun hasDashScopeKey(): Boolean = dashScopeApiKey.isNotBlank()

    fun getLlmApiKey(type: LlmEngineType): String = llmApiKeys[type] ?: ""

    fun hasLlmKey(type: LlmEngineType): Boolean = getLlmApiKey(type).isNotBlank()

    fun getLlmModel(type: LlmEngineType): String = llmModels[type] ?: ""

    fun getAsrCred(type: AsrEngineType, slot: Int): String =
        asrCredentials[type]?.getOrNull(slot) ?: ""

    fun hasAnyCloudAsrKey(): Boolean = hasTingwuKeys() || hasVolcengineKeys()

    fun hasAnyCloudLlmKey(): Boolean = hasArkKey() || hasDashScopeKey() ||
        LlmEngineType.entries.any { it.isCloud && hasLlmKey(it) }
}
