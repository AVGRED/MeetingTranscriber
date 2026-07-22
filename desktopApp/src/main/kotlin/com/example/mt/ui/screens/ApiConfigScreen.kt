package com.example.mt.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.mt.platform.PlatformKeyValueStore

// ── KV 键名 ──
private const val KV_TINGWU_ID = "tingwu_access_key_id"
private const val KV_TINGWU_SECRET = "tingwu_access_key_secret"
private const val KV_TINGWU_APPKEY = "tingwu_app_key"
private const val KV_VOLC_ASR_KEY = "volcengine_asr_api_key"
private const val KV_VOLC_ASR_TOKEN = "volcengine_asr_access_token"
private const val KV_ARK_KEY = "ark_api_key"
private const val KV_ARK_ENDPOINT = "ark_endpoint_id"
private const val KV_DASHSCOPE_KEY = "dashscope_api_key"
private const val KV_DEEPSEEK_KEY = "deepseek_api_key"
private const val KV_KIMI_KEY = "kimi_api_key"
private const val KV_ZHIPU_KEY = "zhipu_api_key"
private const val KV_SILICONFLOW_KEY = "siliconflow_api_key"

@Composable
fun ApiConfigScreen(kvStore: PlatformKeyValueStore) {
    // ── 从 KV Store 加载已有密钥 ──
    var tingwuId by remember { mutableStateOf(kvStore.getString(KV_TINGWU_ID)) }
    var tingwuSecret by remember { mutableStateOf(kvStore.getString(KV_TINGWU_SECRET)) }
    var tingwuAppKey by remember { mutableStateOf(kvStore.getString(KV_TINGWU_APPKEY)) }
    var volcAsrKey by remember { mutableStateOf(kvStore.getString(KV_VOLC_ASR_KEY)) }
    var volcAsrToken by remember { mutableStateOf(kvStore.getString(KV_VOLC_ASR_TOKEN)) }
    var arkKey by remember { mutableStateOf(kvStore.getString(KV_ARK_KEY)) }
    var arkEndpoint by remember { mutableStateOf(kvStore.getString(KV_ARK_ENDPOINT)) }
    var dashScopeKey by remember { mutableStateOf(kvStore.getString(KV_DASHSCOPE_KEY)) }
    var deepseekKey by remember { mutableStateOf(kvStore.getString(KV_DEEPSEEK_KEY)) }
    var kimiKey by remember { mutableStateOf(kvStore.getString(KV_KIMI_KEY)) }
    var zhipuKey by remember { mutableStateOf(kvStore.getString(KV_ZHIPU_KEY)) }
    var siliconflowKey by remember { mutableStateOf(kvStore.getString(KV_SILICONFLOW_KEY)) }

    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("API 配置", style = MaterialTheme.typography.headlineMedium)
        Text(
            "配置云端 ASR 和 LLM 引擎的 API 密钥",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        // ── 通义听悟 ASR ──
        SectionHeader("通义听悟 (ASR)")
        Spacer(Modifier.height(8.dp))
        SecretField("AccessKey ID", tingwuId) { tingwuId = it }
        SecretField("AccessKey Secret", tingwuSecret) { tingwuSecret = it }
        SecretField("AppKey", tingwuAppKey) { tingwuAppKey = it }

        Spacer(Modifier.height(20.dp))

        // ── 豆包 ASR ──
        SectionHeader("豆包 ASR (火山引擎)")
        Spacer(Modifier.height(8.dp))
        SecretField("API Key", volcAsrKey) { volcAsrKey = it }
        SecretField("Access Token", volcAsrToken) { volcAsrToken = it }

        Spacer(Modifier.height(20.dp))

        // ── LLM 引擎 ──
        SectionHeader("豆包 LLM (火山方舟)")
        Spacer(Modifier.height(8.dp))
        SecretField("API Key", arkKey) { arkKey = it }
        OutlinedTextField(
            value = arkEndpoint,
            onValueChange = { arkEndpoint = it },
            label = { Text("Endpoint ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("通义千问 (DashScope)")
        Spacer(Modifier.height(8.dp))
        SecretField("API Key", dashScopeKey) { dashScopeKey = it }

        Spacer(Modifier.height(16.dp))
        SectionHeader("其他 OpenAI 兼容 LLM")
        Spacer(Modifier.height(8.dp))
        SecretField("DeepSeek API Key", deepseekKey) { deepseekKey = it }
        SecretField("Kimi API Key", kimiKey) { kimiKey = it }
        SecretField("智谱 GLM API Key", zhipuKey) { zhipuKey = it }
        SecretField("硅基流动 API Key", siliconflowKey) { siliconflowKey = it }

        Spacer(Modifier.height(32.dp))

        // ── 保存按钮 ──
        Button(
            onClick = {
                kvStore.putString(KV_TINGWU_ID, tingwuId)
                kvStore.putString(KV_TINGWU_SECRET, tingwuSecret)
                kvStore.putString(KV_TINGWU_APPKEY, tingwuAppKey)
                kvStore.putString(KV_VOLC_ASR_KEY, volcAsrKey)
                kvStore.putString(KV_VOLC_ASR_TOKEN, volcAsrToken)
                kvStore.putString(KV_ARK_KEY, arkKey)
                kvStore.putString(KV_ARK_ENDPOINT, arkEndpoint)
                kvStore.putString(KV_DASHSCOPE_KEY, dashScopeKey)
                kvStore.putString(KV_DEEPSEEK_KEY, deepseekKey)
                kvStore.putString(KV_KIMI_KEY, kimiKey)
                kvStore.putString(KV_ZHIPU_KEY, zhipuKey)
                kvStore.putString(KV_SILICONFLOW_KEY, siliconflowKey)
                saved = true
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("保存配置")
        }

        if (saved) {
            Spacer(Modifier.height(8.dp))
            Text("已保存", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "隐藏" else "显示",
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
