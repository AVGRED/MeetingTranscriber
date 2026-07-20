# MeetingTranscriber v3.0

Android 会议录音转写与 AI 纪要生成应用。Kotlin + Room + OkHttp + sherpa-onnx（ASR）+ 云端 LLM（豆包火山方舟）。

## 架构

```
UI (5 Tab: 首页/API配置/会议/历史/设置)
    ↑ ViewModel + StateFlow
UseCase (TranscriptionUseCase / SummaryUseCase)
    ↑
EngineRouter (网络检测 + Key检查)
    ↑
AsrEngine 接口            LlmEngine 接口
├─ FunAsrEngine (离线)    ├─ DoubaoEngine (火山方舟)
├─ FunAsrCloudEngine      ├─ DashScopeEngine (通义千问)
├─ TingwuEngine           └─ 通用 OpenAI 兼容引擎
└─ VolcengineEngine          (DeepSeek/Kimi/智谱/硅基流动)
```

## 构建

```bash
# 1. 配置密钥（可选，运行时也可在 App 内配置）
cp gradle-local.properties.example gradle-local.properties
# 编辑填入 API Key

# 2. 构建
./gradlew assembleDebug
```

不再需要 NDK / CMake / llama.cpp。

## 引擎路由

ASR 默认 FunASR 本地 → 备选多种云端引擎，自动降级。
LLM 纯云端：默认豆包，备选 DashScope/DeepSeek 等，无网或无 Key 时报错（会议结束兜底规则生成器）。

所有 API Key 运行时输入（EncryptedSharedPrefs），支持 `gradle-local.properties` 预置 fallback。

## 关键文件

| 层 | 文件 |
|---|---|
| 引擎接口 | `engine/AsrEngine.kt`, `LlmEngine.kt`, `EngineState.kt` |
| 智能路由 | `engine/EngineRouter.kt` |
| ASR 引擎 | `engine/asr/FunAsrEngine.kt` (离线), `FunAsrCloudEngine.kt`, `TingwuEngine.kt`, `VolcengineEngine.kt` |
| LLM 引擎 | `engine/llm/DoubaoEngine.kt`, `DashScopeEngine.kt`, `OpenAiCompatEngine.kt` |
| Prompt 工具 | `engine/llm/PromptBuilder.kt` |
| UseCase | `domain/TranscriptionUseCase.kt`, `SummaryUseCase.kt` |
| 配置 | `PreferencesManager.kt` (EncryptedSharedPrefs + BuildConfig fallback) |
| 数据库 | `data/db/AppDatabase.kt` (Room + SQLCipher, v8) |
| 网络 | `network/AsrWebSocketClient.kt`, `TingwuApiClient.kt`, `NetworkMonitor.kt` |
| 安全 | `security/CryptoManager.kt` (AndroidKeyStore KEK → DEK) |

## 数据库

Room v8，SQLCipher 加密。明文→加密自动迁移。
`meetings` 表包含 `asrEngineType`, `llmEngineType`, `dialectUsed` 字段。

## 注意事项

- `MeetingSummaryGenerator` 已弃用，仅作为云端 LLM 全部不可用时的规则兜底
- ASR/VAD/声纹模型文件 (`*.onnx`) 不提交 git
- `gradle-local.properties` 不提交 git
