# MeetingTranscriber v3.0 技术文档

## 概述

Android 会议录音转写与 AI 纪要生成应用。ASR 纯本地（无需联网），LLM 纪要走云端。

## 架构

```
UI (5 Tab: 首页/API配置/会议/历史/设置)
    ↑ ViewModel + StateFlow
UseCase (TranscriptionUseCase / SummaryUseCase)
    ↑
EngineRouter (引擎路由 + Key检查)
    ↑
AsrEngine 接口              LlmEngine 接口
├─ FunAsrEngine (离线)      ├─ DoubaoEngine (火山方舟)
├─ FunAsrCloudEngine (ws)   ├─ DashScopeEngine (通义千问)
├─ TingwuEngine (阿里云)    └─ 通用 OpenAI 兼容引擎
└─ VolcengineEngine (火山)     (DeepSeek/Kimi/智谱/硅基流动)
```

## 引擎路由

```
ASR: 默认 FunASR 本地 → Key 已配+有网 → 自动走云端 → 异常降级本地
LLM: 默认豆包云端 → Key 未配/无网 → 报错 → MeetingSummaryGenerator 规则兜底
```

## 实时转写

- **离线引擎**: sherpa-onnx SenseVoiceSmall, 16kHz/16bit/mono
- **双轨解码**: 主 stream 累积 → VAD 停顿 0.8s 出最终句；滑动窗口 250ms interim decode 出实时文本
- **说话人分离**: SileroVAD + VoiceprintIdentifier 声纹识别

## 模型文件

| 文件 | 大小 | 用途 |
|---|---|---|
| `sense-voice-small-cn.onnx` | ~43MB | ASR 离线语音识别 |
| `tokens.txt` | ~KB | 模型词表 |
| `silero_vad.onnx` | ~1.5MB | 神经网络 VAD |

## 构建

```bash
./gradlew assembleDebug
```

不再需要 NDK / CMake / llama.cpp。

## 导出

| 入口 | 位置 |
|---|---|
| 自动导出 | 结束会议 → summaries/ 目录自动存 TXT |
| 历史列表 | 每条会议右边分享图标 → TXT/Word/PDF |
| 会议详情 | 进入详情 → 导出按钮 → TXT/Word/PDF |

## API Key（可选）

| 平台 | 获取地址 |
|---|---|
| 通义听悟 | https://ram.console.aliyun.com/manage/ak |
| 豆包 ASR | https://console.volcengine.com/iam/keymanage |
| 火山方舟 LLM | https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey |
| DashScope | https://dashscope.console.aliyun.com/apiKey |

预置 Key 方式：在 `gradle-local.properties` 中填入 `ARK_API_KEY` 和 `ARK_ENDPOINT_ID`，构建时注入 BuildConfig，运行时自动 fallback。

## 关键文件

| 层 | 文件 |
|---|---|
| ASR 引擎 | `engine/asr/FunAsrEngine.kt` |
| LLM 引擎 | `engine/llm/DoubaoEngine.kt` |
| 引擎路由 | `engine/EngineRouter.kt` |
| 转写用例 | `domain/TranscriptionUseCase.kt` |
| 摘要用例 | `domain/SummaryUseCase.kt` |
| 会议 VM | `ui/meeting/MeetingViewModel.kt` |
| 导出工具 | `ui/export/ExportHelper.kt` |
| 数据库 | `data/db/AppDatabase.kt` (Room + SQLCipher v8) |
| 安全 | `security/CryptoManager.kt` (AndroidKeyStore KEK → DEK) |
