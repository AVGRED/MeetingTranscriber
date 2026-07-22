package com.example.mt.config

/**
 * 所有 PlatformKeyValueStore 键名的统一定义。
 *
 * 避免 ApiConfigScreen、SettingsScreen、MeetingScreen 之间
 * 硬编码字面量导致的不一致风险。
 */
object KvKeys {
    // ── API 密钥 ──
    const val TINGWU_ACCESS_KEY_ID = "tingwu_access_key_id"
    const val TINGWU_ACCESS_KEY_SECRET = "tingwu_access_key_secret"
    const val TINGWU_APP_KEY = "tingwu_app_key"
    const val VOLCENGINE_ASR_API_KEY = "volcengine_asr_api_key"
    const val VOLCENGINE_ASR_ACCESS_TOKEN = "volcengine_asr_access_token"
    const val ARK_API_KEY = "ark_api_key"
    const val ARK_ENDPOINT_ID = "ark_endpoint_id"
    const val DASHSCOPE_API_KEY = "dashscope_api_key"
    const val DEEPSEEK_API_KEY = "deepseek_api_key"
    const val KIMI_API_KEY = "kimi_api_key"
    const val ZHIPU_API_KEY = "zhipu_api_key"
    const val SILICONFLOW_API_KEY = "siliconflow_api_key"

    // ── 偏好设置 ──
    const val PREFERRED_ASR_ENGINE = "preferred_asr_engine"
    const val PREFERRED_LLM_ENGINE = "preferred_llm_engine"
    const val AUTO_FALLBACK = "auto_fallback"
    const val SUMMARY_STYLE = "summary_style"
    const val BACKGROUND_SILENT = "background_silent"
}
