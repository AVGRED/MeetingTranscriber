package com.example.mt.engine.asr

import com.example.mt.config.EngineKeys
import com.example.mt.engine.AsrEngineType

/**
 * 云端实时 ASR 厂家描述：凭证字段（UI 动态生成配置卡片）与获取地址。
 * 各家协议互不兼容，引擎实现见 [CloudAsrWsEngine] 的四个子类。
 */
enum class CloudAsrProvider(
    val type: AsrEngineType,
    /** 凭证字段：(输入框提示, 是否密文显示)，最多 3 个 */
    val fields: List<Pair<String, Boolean>>,
    /** API Key 获取地址（接入说明用） */
    val keyUrl: String,
    /** 接入说明补充 */
    val note: String,
) {
    PARAFORMER(
        type = AsrEngineType.PARAFORMER_CLOUD,
        fields = listOf("API Key（留空则复用通义千问 DashScope 的 Key）" to true),
        keyUrl = "https://dashscope.console.aliyun.com/apiKey",
        note = "与通义千问共用 DashScope API Key：LLM 里配过就无需再填",
    ),
    XFYUN(
        type = AsrEngineType.XFYUN_CLOUD,
        fields = listOf("AppID" to false, "APIKey（实时语音转写专用）" to true),
        keyUrl = "https://console.xfyun.cn/services/rta",
        note = "控制台需开通「实时语音转写」服务（有免费时长）",
    ),
    TENCENT(
        type = AsrEngineType.TENCENT_CLOUD,
        fields = listOf("AppID" to false, "SecretId" to false, "SecretKey" to true),
        keyUrl = "https://console.cloud.tencent.com/cam/capi",
        note = "AppID 在账号中心查看；需开通「语音识别」服务",
    ),
    BAIDU(
        type = AsrEngineType.BAIDU_CLOUD,
        fields = listOf("AppID" to false, "API Key" to true),
        keyUrl = "https://console.bce.baidu.com/ai/#/ai/speech/app/list",
        note = "创建语音技术应用后获得；需开通「实时语音识别」",
    );

    /** 读取指定凭证槽位；Paraformer 空槽回落到 DashScope Key（与 LLM 共用） */
    fun credential(keys: EngineKeys, slot: Int): String {
        val v = keys.getAsrCred(type, slot)
        if (this == PARAFORMER && slot == 0 && v.isBlank()) return keys.dashScopeApiKey
        return v
    }

    /** 全部必填凭证是否已配置 */
    fun hasKeys(keys: EngineKeys): Boolean =
        fields.indices.all { credential(keys, it).isNotBlank() }

    companion object {
        fun of(type: AsrEngineType): CloudAsrProvider? = entries.find { it.type == type }
    }
}
