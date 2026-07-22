package com.example.mt.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mt.engine.AsrEngineType
import com.example.mt.engine.LlmEngineType
import com.example.mt.engine.SummaryStyle
import com.example.mt.platform.PlatformKeyValueStore

// KV Store 键名
private const val KEY_PREFERRED_ASR = "preferred_asr_engine"
private const val KEY_PREFERRED_LLM = "preferred_llm_engine"
private const val KEY_AUTO_FALLBACK = "auto_fallback"
private const val KEY_SUMMARY_STYLE = "summary_style"
private const val KEY_BACKGROUND_SILENT = "background_silent"

@Composable
fun SettingsScreen(kvStore: PlatformKeyValueStore) {
    // 从 KV Store 读取当前设置
    var preferredAsr by remember {
        mutableStateOf(
            try { AsrEngineType.valueOf(kvStore.getString(KEY_PREFERRED_ASR, AsrEngineType.VOLCENGINE_CLOUD.name)) }
            catch (_: Exception) { AsrEngineType.VOLCENGINE_CLOUD }
        )
    }
    var preferredLlm by remember {
        mutableStateOf(
            try { LlmEngineType.valueOf(kvStore.getString(KEY_PREFERRED_LLM, LlmEngineType.DOUBAO_CLOUD.name)) }
            catch (_: Exception) { LlmEngineType.DOUBAO_CLOUD }
        )
    }
    var autoFallback by remember { mutableStateOf(kvStore.getBoolean(KEY_AUTO_FALLBACK, true)) }
    var summaryStyle by remember {
        mutableStateOf(
            try { SummaryStyle.valueOf(kvStore.getString(KEY_SUMMARY_STYLE, SummaryStyle.STANDARD.name)) }
            catch (_: Exception) { SummaryStyle.STANDARD }
        )
    }
    var backgroundSilent by remember { mutableStateOf(kvStore.getBoolean(KEY_BACKGROUND_SILENT, false)) }

    var asrExpanded by remember { mutableStateOf(false) }
    var llmExpanded by remember { mutableStateOf(false) }
    var styleExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        // ── ASR 引擎选择 ──
        Text("首选 ASR 引擎", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = asrExpanded,
            onExpandedChange = { asrExpanded = it },
        ) {
            OutlinedTextField(
                value = preferredAsr.displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = asrExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = asrExpanded,
                onDismissRequest = { asrExpanded = false },
            ) {
                AsrEngineType.entries.forEach { engineType ->
                    DropdownMenuItem(
                        text = { Text(engineType.displayName) },
                        onClick = {
                            preferredAsr = engineType
                            kvStore.putString(KEY_PREFERRED_ASR, engineType.name)
                            asrExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── LLM 引擎选择 ──
        Text("首选 LLM 引擎", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = llmExpanded,
            onExpandedChange = { llmExpanded = it },
        ) {
            OutlinedTextField(
                value = preferredLlm.displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = llmExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = llmExpanded,
                onDismissRequest = { llmExpanded = false },
            ) {
                LlmEngineType.entries.forEach { engineType ->
                    DropdownMenuItem(
                        text = { Text(engineType.displayName) },
                        onClick = {
                            preferredLlm = engineType
                            kvStore.putString(KEY_PREFERRED_LLM, engineType.name)
                            llmExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── 纪要风格 ──
        Text("默认纪要风格", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = styleExpanded,
            onExpandedChange = { styleExpanded = it },
        ) {
            OutlinedTextField(
                value = summaryStyle.label,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = styleExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = styleExpanded,
                onDismissRequest = { styleExpanded = false },
            ) {
                SummaryStyle.entries.forEach { style ->
                    DropdownMenuItem(
                        text = { Text(style.label) },
                        onClick = {
                            summaryStyle = style
                            kvStore.putString(KEY_SUMMARY_STYLE, style.name)
                            styleExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── 开关项 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("自动降级", style = MaterialTheme.typography.titleSmall)
                Text(
                    "密钥缺失时自动切换可用引擎",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoFallback,
                onCheckedChange = {
                    autoFallback = it
                    kvStore.putBoolean(KEY_AUTO_FALLBACK, it)
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("后台静默", style = MaterialTheme.typography.titleSmall)
                Text(
                    "后台录制时不显示通知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = backgroundSilent,
                onCheckedChange = {
                    backgroundSilent = it
                    kvStore.putBoolean(KEY_BACKGROUND_SILENT, it)
                },
            )
        }
    }
}
