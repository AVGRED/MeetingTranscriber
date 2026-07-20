# 会议转写

Android 会议录音转写与 AI 纪要生成应用。离线 ASR + 云端 LLM，支持 8 种语音引擎和 6 种大模型。

<p align="center">
  <strong>重庆柔晶科技有限公司</strong>
</p>

## 功能

- **实时转写** — 离线 sherpa-onnx SenseVoiceSmall，断网也能用；云端 FunASR / 通义听悟 / 豆包 / Paraformer / 讯飞 / 腾讯云 / 百度自动降级
- **AI 纪要** — 豆包(火山方舟) / 通义千问(DashScope) / DeepSeek / Kimi / 智谱 / 硅基流动，支持标准/要点/决策三种风格
- **说话人分离** — Silero 神经 VAD + 声纹识别自动标注，手动重命名
- **录音回放** — 加密 WAV 存档，详情页 MediaPlayer 播放
- **拍照归档** — 内建相机（Camera2 直拍），绕过 OEM EXIF bug；相册多选分享删除
- **崩溃恢复** — 每 5 秒持久化状态，异常退出后可恢复
- **导出** — TXT / Word / PDF / 局域网扫码下载
- **数据安全** — AndroidKeyStore → SQLCipher 数据库加密 + EncryptedFile 录音加密

## 架构

```
UI (5 Tab: 首页 / API配置 / 会议 / 历史 / 设置)
    ↑ ViewModel + StateFlow
UseCase (TranscriptionUseCase / SummaryUseCase)
    ↑
EngineRouter (网络检测 + Key检查 + 自动降级)
    ↑
AsrEngine 接口              LlmEngine 接口
├─ FunAsrEngine (离线)      ├─ DoubaoEngine (火山方舟)
├─ FunAsrCloudEngine        ├─ DashScopeEngine (通义千问)
├─ TingwuEngine             └─ OpenAiCompatEngine
├─ VolcengineEngine             (DeepSeek/Kimi/智谱/硅基流动)
├─ ParaformerEngine
├─ XfyunEngine
├─ TencentAsrEngine
└─ BaiduAsrEngine
```

## 构建

```bash
# 1. 配置 API Key（可选，运行时也可在 App 内配置）
cp gradle-local.properties.example gradle-local.properties
# 编辑填入密钥

# 2. 构建
./gradlew assembleDebug
```

> 不再需要 NDK / CMake。sherpa-onnx 模型文件 (`*.onnx`) 不提交 git，首次启动自动从 assets 加载。

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin |
| UI | ViewBinding + Material 3 + RecyclerView |
| 架构 | MVVM (AndroidViewModel + StateFlow + UseCase) |
| 数据库 | Room + SQLCipher (v9, 加密 + 明文自动迁移) |
| 网络 | OkHttp + WebSocket |
| ASR | sherpa-onnx SenseVoiceSmall + 7 种云端引擎 |
| VAD | Silero VAD (ONNX) + 能量 VAD 回退 |
| 加密 | AndroidKeyStore KEK → DEK → EncryptedSharedPrefs / SQLCipher / EncryptedFile |
| 局域网 | NanoHTTPD + ZXing 二维码 |
| 构建 | Gradle 8.7 + KSP + AGP 8.7 |

## 权限

| 权限 | 用途 |
|---|---|
| `RECORD_AUDIO` | 会议录音 |
| `CAMERA` | 相册拍照（Camera2 直拍，可拒绝） |
| `INTERNET` | 云端 ASR/LLM |
| `POST_NOTIFICATIONS` | 前台服务通知 (Android 13+) |
| `FOREGROUND_SERVICE_MICROPHONE` | 会议录音前台服务 |
| `READ_MEDIA_IMAGES` | 相册读取旧照片 (Android 13+，可选) |

## 项目结构

```
app/src/main/java/com/example/meetingtranscriber/
├── audio/         音频采集 / VAD / 声纹 / WAV 编解码
├── data/
│   ├── db/        Room 数据库 (9 张表, v9)
│   ├── model/     数据模型
│   └── repository/ 数据仓库
├── domain/        TranscriptionUseCase / SummaryUseCase
├── engine/
│   ├── asr/       8 种 ASR 引擎
│   └── llm/       6 种 LLM 引擎 + PromptBuilder
├── network/       WebSocket / 云同步 / 局域网分享 / 更新检查
├── security/      CryptoManager (密钥管理)
├── ui/
│   ├── album/     相册
│   ├── apiconfig/  API 配置
│   ├── detail/    会议详情
│   ├── export/    导出 / 扫码分享
│   ├── history/   历史列表
│   ├── home/      首页 + Camera2 拍照
│   ├── meeting/   会议进行中
│   └── settings/  设置
└── util/          工具类
```

## 引擎路由

- **ASR** 默认 FunASR 本地 → 网络可用时按用户偏好选择云端引擎 → 无 Key 自动降级
- **LLM** 纯云端，默认豆包 → 无网/无 Key 回退内置规则生成器
- 所有 API Key 运行时输入（EncryptedSharedPrefs），支持 `gradle-local.properties` 预置 fallback
