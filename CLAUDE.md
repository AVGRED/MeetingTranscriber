# MeetingTranscriber v2.0

Android 会议录音转写与纪要生成应用。Kotlin + Room + OkHttp + sherpa-onnx + llama.cpp。

## 架构

```
UI (5 Tab: 首页/API配置/会议/历史/设置)
    ↑ ViewModel + StateFlow
UseCase (TranscriptionUseCase / SummaryUseCase)
    ↑
EngineRouter (网络检测 + Key检查 + 自动降级)
    ↑
AsrEngine 接口            LlmEngine 接口
├─ FunAsrEngine (离线)    ├─ QwenEngine (本地 llama.cpp)
├─ FunAsrCloudEngine      ├─ DoubaoEngine (火山方舟)
├─ TingwuEngine           └─ DashScopeEngine (通义千问)
└─ VolcengineEngine
```

## 构建

```bash
# 1. 准备 llama.cpp（一次性）
cd app/src/main/cpp
git clone https://github.com/ggerganov/llama.cpp.git
cd llama.cpp && git checkout <latest-release>

# 2. 配置密钥
cp gradle-local.properties.example gradle-local.properties
# 编辑填入 API Key（可选，运行时也可在 App 内配置）

# 3. 构建
./gradlew assembleDebug
```

## 引擎路由

ASR 默认 FunASR 云端 → 备选通义听悟/豆包 → 兜底 FunASR 离线。
LLM 默认 Qwen 本地 (llama.cpp JNI) → 备选豆包/通义千问。

所有 API Key 运行时输入（EncryptedSharedPrefs），不写死 BuildConfig。

## 关键文件

| 层 | 文件 |
|---|---|
| 引擎接口 | `engine/AsrEngine.kt`, `LlmEngine.kt`, `EngineState.kt` |
| 智能路由 | `engine/EngineRouter.kt` |
| ASR 引擎 | `engine/asr/FunAsrEngine.kt` (离线), `FunAsrCloudEngine.kt`, `TingwuEngine.kt`, `VolcengineEngine.kt` |
| LLM 引擎 | `engine/llm/QwenEngine.kt`, `DoubaoEngine.kt`, `DashScopeEngine.kt` |
| 模型管理 | `engine/llm/ModelDownloadManager.kt`, `engine/llm/LlmNative.kt` |
| UseCase | `domain/TranscriptionUseCase.kt`, `SummaryUseCase.kt` |
| 配置 | `PreferencesManager.kt` (EncryptedSharedPrefs) |
| 数据库 | `data/db/AppDatabase.kt` (Room + SQLCipher, v8) |
| 网络 | `network/AsrWebSocketClient.kt`, `TingwuApiClient.kt`, `NetworkMonitor.kt` |
| 安全 | `security/CryptoManager.kt` (AndroidKeyStore KEK → DEK) |

## 数据库

Room v8，SQLCipher 加密。明文→加密自动迁移。
`meetings` 表包含 `asrEngineType`, `llmEngineType`, `dialectUsed` 字段。

## 注意事项

- `llama.cpp` 是嵌套 git 仓库，已 gitignore，需手动 clone
- `MeetingSummaryGenerator` 已弃用但被 ViewModel 作为 fallback 引用，暂不能删
- 模型文件 (`*.onnx`, `*.gguf`) 不提交 git
- `assets/models/` 需手动放置：ASR（`sense-voice-small-cn.onnx` + `tokens.txt`）、声纹（`3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`，26MB，从 sherpa-onnx releases `speaker-recongition-models` 下载）、VAD（`silero_vad.onnx`，0.6MB，从 sherpa-onnx releases `asr-models` 下载）
- `gradle-local.properties` 不提交 git
- NDK 仅编译 `arm64-v8a`

## 行为准则

### 1. 先思考再写代码
- 实现之前先说明假设，不确定就问
- 有多种方案时列出对比，不要默选
- 有困惑时停下来，说清楚哪里不清楚

### 2. 简单优先
- 只写解决问题所需的最小代码
- 不为单次使用创建抽象层
- 不添加未要求的"灵活性"或"可配置性"
- 不处理不可能发生的错误场景

### 3. 精准改动
- 只改必须改的，不顺手"改进"相邻代码
- 不重构没有 bug 的代码
- 匹配现有代码风格
- 自己的改动产生的孤立引用必须清理

### 4. 目标驱动
- 把任务变成可验证的目标
- 每一步说明改什么、验证什么
- 一次做好，不要半途而废
