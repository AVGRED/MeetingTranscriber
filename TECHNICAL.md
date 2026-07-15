# MeetingTranscriber v2.0 技术文档

## 概述

Android 离线会议录音转写与 AI 纪要生成应用。全程离线、无需联网、无需 Key。

## 架构

```
UI (5 Tab: 首页/API配置/会议/历史/设置)
    ↑ ViewModel + StateFlow
UseCase (TranscriptionUseCase / SummaryUseCase)
    ↑
EngineRouter (引擎路由 + 自动降级)
    ↑
AsrEngine 接口              LlmEngine 接口
├─ FunAsrEngine (离线)      ├─ QwenEngine (本地 llama.cpp)
├─ FunAsrCloudEngine (ws)   ├─ DoubaoEngine (火山方舟)
├─ TingwuEngine (阿里云)    └─ DashScopeEngine (通义千问)
└─ VolcengineEngine (火山)
```

## 引擎路由

```
默认 FunASR 本地 → 无需任何配置
云端 Key 已配 + 有网 → 自动走云端
云端异常 + 自动降级开 → 本地兜底（不会报错）
```

## 实时转写

- **离线引擎**: sherpa-onnx SenseVoiceSmall, 16kHz/16bit/mono
- **双轨解码**: 主 stream 累积 → VAD 停顿 0.8s 出最终句；滑动窗口 250ms interim decode 出实时文本
- **锁策略**: engineLock → decodeLock 顺序一致，dispose 持 decodeLock 防竞态

## 模型文件

| 文件 | 大小 | 用途 |
|---|---|---|
| `sense-voice-small-cn.onnx` | ~43MB | ASR 离线语音识别 |
| `tokens.txt` | ~KB | 模型词表 |
| `qwen2.5-xxx.gguf` | ~1.1GB | 本地 LLM 摘要（可选下载） |

## 构建

```bash
# 1. 准备 llama.cpp（如需本地 LLM）
cd app/src/main/cpp
git clone https://github.com/ggerganov/llama.cpp.git

# 2. 构建
./gradlew assembleDebug
```

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

## 关键文件

| 层 | 文件 |
|---|---|
| ASR 引擎 | `engine/asr/FunAsrEngine.kt` |
| LLM 引擎 | `engine/llm/QwenEngine.kt` |
| 引擎路由 | `engine/EngineRouter.kt` |
| 转写用例 | `domain/TranscriptionUseCase.kt` |
| 摘要用例 | `domain/SummaryUseCase.kt` |
| 会议 VM | `ui/meeting/MeetingViewModel.kt` |
| 导出工具 | `ui/export/ExportHelper.kt` |
| 数据库 | `data/db/AppDatabase.kt` (Room + SQLCipher v8) |
| 安全 | `security/CryptoManager.kt` (AndroidKeyStore KEK → DEK) |
