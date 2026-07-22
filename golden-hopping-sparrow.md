# MeetingTranscriber → Windows Desktop 迁移计划

## Context

将现有的 Android 会议转写 App（`C:\Users\H\MeetingTranscriber`）移植到 Windows 桌面平台。项目共 **13,844 行 Kotlin**，**75 个源文件**，采用 Kotlin Multiplatform + Compose Desktop 方案，**约 70% 的业务代码可直接复用**。

### 关键数据

| 指标 | 数值 |
|------|------|
| 总代码行数 | 13,844 |
| 源文件数 | ~75 |
| `android.*` 导入 | 217 处 (55 个文件) |
| `androidx.*` 导入 | 107 处 (39 个文件) |
| 零 Android 依赖的文件 | ~20 个（纯 Kotlin） |
| ASR 引擎 | 6 个 (全部云端) |
| LLM 引擎 | 6 个 (全部云端) |
| Room 表 | 7 张 (8 次迁移) |
| XML 布局文件 | 14 个 |

### 团队分工：5 人

| 成员 | 负责模块 | 依赖关系 |
|------|---------|---------|
| A | 共享核心层 (commonMain) | 无依赖，可最先开始 |
| B | 音频采集 & 声纹识别 | 依赖 A 的接口定义 |
| C | 数据存储 & 安全加密 | 依赖 A 的接口定义 |
| D | Compose Desktop UI | 依赖 A+B+C 的功能可用 |
| E | 构建系统 & 集成打包 | 全程支持，收尾集成 |

---

### 零改动直接复用的文件 (20 个，纯 Kotlin，零 Android 依赖)

这些文件可以直接复制到 `commonMain`，不需要任何修改：

| 文件 | 行数 | 说明 |
|------|------|------|
| `engine/EngineConstants.kt` | 17 | 缓冲区大小、重连参数常量 |
| `engine/EngineState.kt` | 32 | IDLE/LOADING/READY/RUNNING/ERROR 枚举 |
| `engine/llm/PromptBuilder.kt` | 72 | 三种纪要风格的 Prompt 构建 |
| `audio/VADDetector.kt` | ~70 | 能量阈值 VAD，纯数学 |
| `audio/Resampler.kt` | ~57 | 48kHz立体声→16kHz单声道，纯数学 |
| `util/TextFormatter.kt` | ~84 | 标点规范化 + 中文数字口语转写 |
| `util/TopicSegmenter.kt` | ~24 | 基于时间间隙的话题分段 |
| `data/model/MeetingInfo.kt` | 63 | 会议信息数据类 |
| `data/model/TranscriptSegment.kt` | 33 | 转写段落数据类 |
| `network/AsrTypes.kt` | ~30 | 连接状态枚举 + 句子结果 |
| `ui/meeting/MeetingSummaryGenerator.kt` | 40 | 规则兜底纪要生成器 |
| `engine/asr/CloudAsrProvider.kt` | 70 | 各家 ASR 厂家元数据枚举 |
| `network/AsrWebSocketClient.kt` | ~275 | 通义听悟 WebSocket 客户端 (纯 OkHttp) |
| `network/AliyunSigner.kt` | ~100 | 阿里云 HMAC-SHA1 签名（仅 Base64 需替换） |
| `network/TingwuApiClient.kt` | ~205 | 通义听悟 REST API (纯 OkHttp) |
| `network/AuthTokenManager.kt` | ~80 | JWT Token 管理 |
| `network/LanShareServer.kt` | ~254 | NanoHTTPD 局域网分享（纯 Java 库） |
| `network/UpdateChecker.kt` | ~60 | 版本更新检查 |
| `network/VocabularyApiClient.kt` | ~60 | 热词 API |
| `ui/meeting/MeetingUiState.kt` | 41 | UI 状态数据类 |

**小计：~1,600 行，零改动。**

### 仅需替换 `android.util.Log` 的文件 (~800 行)

引入 `io.github.aakira:napier:2.7.1` 统一日志（KMP 支持）：

| 文件 | 改动 |
|------|------|
| `engine/asr/ReconnectHandler.kt` (52行) | `Log.i/e/w` → `Napier.i/e/w` |
| `engine/llm/DoubaoEngine.kt` (195行) | 同上 |
| `engine/llm/DashScopeEngine.kt` (~150行) | 同上 |
| `engine/llm/OpenAiCompatEngine.kt` (252行) | 同上 |
| `engine/asr/VolcengineEngine.kt` (~500行) | 同上 + `initialize(Context)` |

其他引擎同理。**替换模式一致，一个脚本可批量完成。**

---

## Part A: 共享核心层提取 (成员 A)

**目标**：将所有与平台无关的代码提取到 KMP `commonMain`，定义 `expect/actual` 接口。

### A1. 创建共享模块骨架

```
meeting-transcriber-desktop/
├── shared/
│   └── src/
│       ├── commonMain/kotlin/com/example/mt/
│       ├── androidMain/kotlin/com/example/mt/
│       └── desktopMain/kotlin/com/example/mt/
├── desktopApp/
└── androidApp/   (渐进迁移，保留现有代码引用)
```

创建 `shared/build.gradle.kts`：KMP 插件 + OkHttp(4.12.0) + kotlinx-coroutines-core + kotlinx-serialization-json + kotlinx-datetime。

### A2. 定义 expect/actual 接口（最先做，解锁 B/C 并行）

在 `commonMain/kotlin/com/example/mt/platform/` 下定义三个接口：

```kotlin
// PlatformAudioCapture.kt — expect 声明
expect class PlatformAudioCapture() {
    fun start(): Boolean
    fun stop()
    val audioStream: Flow<ByteArray>  // SharedFlow, 16kHz/mono/16bit PCM
    fun hasPermission(): Boolean
}

// PlatformKeyValueStore.kt — expect 声明
expect class PlatformKeyValueStore(name: String) {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

// PlatformDatabase.kt — expect 声明
expect class PlatformDatabase {
    // 暴露 DAO 接口
}
```

以及：
- `VoiceprintModelLoader` — expect/actual，Android 用 assets，Desktop 用文件系统加载
- `NetworkMonitor` — expect/actual，Android 用 ConnectivityManager，Desktop 用 `java.net.InetAddress`
- `FileAccess` — expect/actual，文件路径访问

### A3. 迁移引擎层 (零改动，直接复制)

以下文件 **直接复制** 到 `commonMain`，无需修改（纯 Kotlin + OkHttp）：

| 文件 | 行数 | 说明 |
|------|------|------|
| `engine/AsrEngine.kt` | 106 | **需改**: `initialize(Context)` → `initialize()` |
| `engine/LlmEngine.kt` | 70 | **需改**: `initialize(Context)` → `initialize()` |
| `engine/EngineRouter.kt` | 238 | **需改**: 去掉 Context 参数，改用配置注入 |
| `engine/EngineState.kt` | 32 | ✅ 零改动 |
| `engine/EngineConstants.kt` | 17 | ✅ 零改动 |
| `engine/asr/VolcengineEngine.kt` | ~500 | **需改**: `initialize(Context)` → `initialize()` |
| `engine/asr/TingwuEngine.kt` | 170 | **需改**: `initialize(Context)` → `initialize()` |
| `engine/asr/CloudAsrWsEngine.kt` | 350 | **需改**: `initialize(Context)` → `initialize()` |
| `engine/asr/CloudAsrProvider.kt` | 70 | ✅ 零改动 |
| `engine/asr/ParaformerEngine.kt` | ~80 | **需改**: 去掉 `android.util.Log` → `kotlin-logging` 或 println |
| `engine/asr/XfyunEngine.kt` | ~80 | 同上 |
| `engine/asr/TencentAsrEngine.kt` | ~80 | 同上 |
| `engine/asr/BaiduAsrEngine.kt` | ~80 | 同上 |
| `engine/asr/ReconnectHandler.kt` | 52 | **需改**: `android.util.Log` → 公共 logger |
| `engine/llm/DoubaoEngine.kt` | 195 | **需改**: `initialize(Context)` + Log |
| `engine/llm/DashScopeEngine.kt` | ~150 | 同上 |
| `engine/llm/OpenAiCompatEngine.kt` | 252 | 同上 |
| `engine/llm/PromptBuilder.kt` | 72 | ✅ 零改动 |

**改动模式**：所有引擎的改动都是一致的机械操作：
1. `initialize(context: Context)` → `initialize()`
2. `android.util.Log` → 引入 `kermit` 或 `napier` 公共 logger（在 commonMain 中预置）
3. `PreferencesManager` 引用 → 改为通过构造函数注入密钥配置（一个简单的 data class）

### A4. 迁移 domain 层

| 文件 | 行数 | 改动 |
|------|------|------|
| `domain/TranscriptionUseCase.kt` | 257 | 去掉 `Context` 参数，其余逻辑完全不变 |
| `domain/SummaryUseCase.kt` | 175 | 同上 |

### A5. 迁移 network 层

| 文件 | 行数 | 改动 |
|------|------|------|
| `network/TingwuApiClient.kt` | ~200 | 去掉 BuildConfig 引用，改为注入密钥 |
| `network/AsrWebSocketClient.kt` | ~150 | 去掉 android.util.Log |
| `network/AliyunSigner.kt` | ~100 | ✅ 纯 Java stdlib，零改动 |
| `network/AsrTypes.kt` | ~30 | ✅ 零改动 |
| `network/AuthTokenManager.kt` | ~80 | 去掉 android.util.Log |
| `network/UpdateChecker.kt` | ~60 | 去掉 Android 依赖，Desktop 永久返回 null |
| `network/LanShareServer.kt` | ~80 | NanoHTTPD 是纯 Java，Desktop 可直接用 |
| `network/CloudSyncManager.kt` | ~80 | 暂不迁移，Desktop Phase 2 |

### A6. 迁移 util 层

| 文件 | 行数 | 改动 |
|------|------|------|
| `util/TextFormatter.kt` | ~100 | ✅ 零改动 |
| `util/TopicSegmenter.kt` | ~80 | ✅ 零改动 |
| `util/LanguageOption.kt` | ~30 | ✅ 零改动 |
| `util/DebounceTextWatcher.kt` | ~30 | Desktop 用 Compose 自带 debounce，无需迁移 |

### A7. 定义配置模型（替代 PreferencesManager）

在 commonMain 中创建一个纯数据类：
```kotlin
data class EngineKeys(
    // ASR
    val tingwuAccessKeyId: String,
    val tingwuAccessKeySecret: String,
    val tingwuAppKey: String,
    val volcengineAsrApiKey: String,
    val volcengineAsrAccessToken: String,
    // LLM
    val arkApiKey: String,
    val arkEndpointId: String,
    val dashScopeApiKey: String,
    // Generic ASR credentials
    val asrCredentials: Map<AsrEngineType, List<String>>,
    // Generic LLM keys
    val llmKeys: Map<LlmEngineType, String>,
    val llmModels: Map<LlmEngineType, String>,
    // Preferences
    val preferredAsrEngine: AsrEngineType,
    val preferredLlmEngine: LlmEngineType,
    val autoFallback: Boolean,
    val summaryStyle: SummaryStyle,
)
```

### A8. 产出物清单（成员 A）

- `shared/src/commonMain/` 下完整可编译的引擎/domain/network/util 代码
- `shared/src/commonMain/platform/` 下 expect 声明（供 B、C 实现）
- `shared/src/androidMain/` 下 actual 实现（桥接回原 Android 代码）
- `shared/src/desktopMain/` 下 actual 桩代码（供 B、C 填充）

---

## Part B: 音频采集 & 声纹识别 (成员 B)

**目标**：实现 Windows 端的音频采集、WAV 录音、声纹识别。

### B1. Windows 音频采集 (actual `PlatformAudioCapture`)

**技术方案**：`javax.sound.sampled` (Java Sound API)

- Java Sound API 是 JDK 内置的，无需额外依赖
- 支持 16kHz/16bit/mono 的 `TargetDataLine`
- windows 上底层调用 WASAPI

核心实现 (`desktopMain/kotlin/com/example/mt/platform/DesktopAudioCapture.kt`)：

```kotlin
actual class PlatformAudioCapture {
    private val format = AudioFormat(16000f, 16, 1, true, false)
    private var line: TargetDataLine? = null
    private val _audioStream = MutableSharedFlow<ByteArray>(...)
    
    actual fun start(): Boolean {
        line = AudioSystem.getTargetDataLine(format)
        line!!.open(format, CHUNK_SIZE * 4)
        line!!.start()
        // 启动协程循环读取，emit 到 SharedFlow
    }
    actual fun stop() { line?.stop(); line?.close() }
    actual fun hasPermission(): Boolean = true  // Desktop 无需权限
}
```

关键参数：与 Android 端一致 — `CHUNK_SIZE=3200` (100ms), `SAMPLE_RATE=16000`, mono, 16bit。

**风险缓解**：若 Java Sound 在某些声卡上不可用，备用方案是通过 JNA 调用 Windows Core Audio API (WASAPI)。JNA 依赖已成熟（`net.java.dev.jna:jna:5.14.0`）。

### B2. WAV 录音适配

`WavRecorder` 当前依赖 `EncryptedFile` (AndroidX Security)。需要拆分为：

- **commonMain**：`WavHeaderBuilder`（零改动，已经纯 Kotlin）+ WAV 数据写入接口
- **desktopMain**：明文 WAV 写 `RandomAccessFile`（就是原 Android 的明文路径）
- 加密模式：Desktop 上使用自定义 AES-GCM 加密（不再依赖 AndroidKeyStore），A 的 `PlatformKeyValueStore` 提供密钥存储。

涉及文件：
| 文件 | 改动 |
|------|------|
| `audio/WavHeaderBuilder.kt` | ✅ 直接复用 |
| `audio/WavRecorder.kt` | 拆分为 common 接口 + desktop actual |
| `audio/WavPlayer.kt` | 拆分，Desktop 用 `AudioSystem.getSourceDataLine()` 播放 |
| `audio/Resampler.kt` | ✅ 零改动，纯 Kotlin |
| `audio/VADDetector.kt` | ✅ 零改动，纯 Kotlin |
| `audio/AudioCacheManager.kt` | ✅ 零改动，纯文件操作 |

### B3. 声纹识别 (sherpa-onnx Windows)

**技术方案**：sherpa-onnx 提供 Windows x64 预编译 DLL。

关键步骤：
1. 下载 sherpa-onnx Windows 预编译包 (从 GitHub releases)
2. 获取 `sherpa-onnx-jni.dll` + `onnxruntime.dll` (Windows x64)
3. 通过 JNA/JNI 加载 DLL
4. 实现 `VoiceprintIdentifier` 的 desktop actual：
   ```kotlin
   actual class VoiceprintModelLoader {
       actual fun load(config: ModelConfig): SpeakerEmbeddingExtractor {
           // 不使用 Android assets API
           // 改用 sherpa-onnx 的文件系统加载 API
           // SpeakerEmbeddingExtractor(modelPath, config)
       }
   }
   ```

**注意**：sherpa-onnx 的 Android AAR 和 Windows DLL 的 JNI 接口是同一套（`com.k2fsa.sherpa.onnx` 包名），所以 `VoiceprintIdentifier.kt` 的 core logic（embedding 计算、余弦匹配、说话人管理）可以原样保留（commonMain），只有模型加载方式不同（actual 实现）。当前文件 214 行，其中 `identify()` 方法（101-151 行）的核心逻辑几乎不动。

**用于声纹识别的 sherpa-onnx 共享代码**：提取 `VoiceprintIdentifier.kt` 中的扬声器管理逻辑到 common，expect 模型加载器供 B 实现 desktop 版本。

### B4. 产出物清单（成员 B）

- `desktopMain/.../platform/DesktopAudioCapture.kt` — 完整实现
- `desktopMain/.../platform/DesktopVoiceprintLoader.kt` — 文件系统加载 sherpa-onnx
- `desktopMain/.../audio/DesktopWavRecorder.kt` — 明文 WAV + 可选密写
- `desktopMain/.../audio/DesktopWavPlayer.kt` — Java Sound 回放
- Windows 预编译 sherpa-onnx DLLs

---

## Part C: 数据存储 & 安全加密 (成员 C)

**目标**：Windows 端数据库、配置存储、加密。

### C1. 数据库方案：SQLDelight

**选择理由**：
- KMP 原生支持，Desktop 目标成熟
- 生成的 Kotlin 代码类型安全
- SQLite 在 Windows 上通过 JDBC (sqlite-jdbc) 驱动

**迁移步骤**：
1. 将 Room entities 转写为 `.sq` 文件：

   ```sql
   -- meeting.sq
   CREATE TABLE meetings (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       title TEXT NOT NULL,
       startTime INTEGER NOT NULL,
       endTime INTEGER,
       durationSeconds INTEGER NOT NULL DEFAULT 0,
       speakerCount INTEGER NOT NULL DEFAULT 0,
       segmentCount INTEGER NOT NULL DEFAULT 0,
       summary TEXT,
       isOffline INTEGER NOT NULL DEFAULT 0,
       audioFilePath TEXT,
       isArchived INTEGER NOT NULL DEFAULT 0,
       archivedAt INTEGER,
       tag TEXT,
       asrEngineType TEXT,
       llmEngineType TEXT,
       dialectUsed TEXT
   );
   
   selectAll: SELECT * FROM meetings ORDER BY startTime DESC;
   insert: INSERT INTO meetings (...) VALUES (...);
   updateSummary: UPDATE meetings SET summary = ? WHERE id = ?;
   ```

2. 同样定义 `transcript_segments`, `recovery_state`, `vocabulary` 等表
3. 实现 `MeetingRepository` / `TranscriptRepository`（当前 Android 代码 60-80% 逻辑复用）

**涉及迁移的 Room 文件**：
| 原文件 | 新文件 (commonMain) | 改动量 |
|--------|---------------------|--------|
| `db/MeetingEntity.kt` | → `.sq` 表定义 | 重写为 SQL |
| `db/TranscriptEntity.kt` | → `.sq` 表定义 | 同上 |
| `db/RecoveryStateEntity.kt` | → `.sq` 表定义 | 同上 |
| `db/VocabularyEntity.kt` | 暂不迁移 | Phase 2 |
| `db/MeetingDao.kt` | SQLDelight 自动生成 | 删 |
| `db/TranscriptDao.kt` | 同上 | 删 |
| `repository/MeetingRepository.kt` | → `commonMain/data/repository/` | 适配 SQLDelight API |
| `repository/TranscriptRepository.kt` | → `commonMain/data/repository/` | 同上 |
| `db/AppDatabase.kt` (344行) | 删除，SQLDelight 替代 | 删 |
| `db/Migration_*` (8个迁移) | 新数据库从零开始，无需迁移 | 删 |

### C2. 配置存储 (actual `PlatformKeyValueStore`)

**desktopMain 实现**：
```kotlin
actual class PlatformKeyValueStore(name: String) {
    private val prefs = java.util.prefs.Preferences.userRoot().node("mt/$name")
    // API keys 存储：AES-GCM 加密后写入文件
    // 普通设置：直接写 Preferences XML
}
```

密钥安全存储：使用 `javax.crypto` (JDK 内置) 实现 AES-256-GCM 加密，密钥派生自 Windows DPAPI (通过 JNA 调用 `CryptProtectData`)。

### C3. 加密系统替代

当前 `CryptoManager.kt` (183行) 的 Android 依赖：
- `AndroidKeyStore` → Windows DPAPI (JNA) 或纯文件密钥
- `EncryptedSharedPreferences` → 自定义 AES-GCM 加密文件
- `EncryptedFile` → 自定义 AES-GCM 流加密

Desktop 简化方案：
```
DesktopKeyStore
    └─ DPAPI-protected master key file
         └─ AES-256 DEK (加密数据库密码和文件密钥)
```

**注**：对于 MVP，可先使用明文存储（desktop 是个人设备，安全威胁模型不同），加密作为 Phase 2。

### C4. 数据模型复用

以下文件 **零改动** 直接复制到 commonMain：

| 文件 | 说明 |
|------|------|
| `data/model/MeetingInfo.kt` | ✅ 纯 Kotlin，依赖 `java.text.SimpleDateFormat` & `java.util.Date`（JDK 内置） |
| `data/model/TranscriptSegment.kt` | ✅ 纯 Kotlin |

### C5. 产出物清单（成员 C）

- `shared/src/commonMain/sqldelight/` 下所有 `.sq` 文件
- `shared/src/commonMain/.../repository/` — MeetingRepository, TranscriptRepository (适配 SQLDelight)
- `shared/src/desktopMain/.../platform/DesktopKeyValueStore.kt`
- `shared/src/desktopMain/.../platform/DesktopDatabase.kt` — SQLDelight driver 初始化
- `shared/src/desktopMain/.../security/DesktopKeyStore.kt` — Windows 密钥存储

---

## Part D: Compose Desktop UI (成员 D)

**目标**：用 Compose Multiplatform 重写全部 UI。

### D1. 窗口框架

```kotlin
// desktopApp/src/main/kotlin/com/example/mt/Main.kt
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Meeting Transcriber",
        state = rememberWindowState(width = 900.dp, height = 700.dp)
    ) {
        MaterialTheme {
            App()
        }
    }
}
```

### D2. 页面清单（对应 Android 5个 Tab）

| Android | Desktop 实现 | 说明 |
|---------|-------------|------|
| `HomeFragment` + `HomeViewModel` | `HomeScreen` | 首页仪表板 |
| `ApiConfigFragment` + `ApiConfigViewModel` | `ApiConfigScreen` | API 密钥配置表单 |
| `MeetingFragment` + `MeetingViewModel` | **`MeetingScreen` + `MeetingViewModel`** | **核心页面** |
| `HistoryFragment` + `HistoryViewModel` | `HistoryScreen` | 会议历史列表 |
| `SettingsFragment` + `SettingsViewModel` | `SettingsScreen` | 引擎偏好、自动降级 |

导航方案：Compose Desktop 无 `BottomNavigationView`，用侧边导航栏 `NavigationRail` 或顶部 Tab Row。

### D3. 核心页面：MeetingScreen

这是最复杂的一页（Android `MeetingViewModel` 1075 行，`MeetingFragment` 407 行）。

**可复用的逻辑**（ViewHolder 的 `MeetingViewModel.kt` 可以 ~80% 搬到 commonMain）：
- 音频管线扇出（wavChannel + procChannel）
- VAD 切轮 + 声纹调度
- ASR 句子收集 + speaker 标注
- 恢复机制（每 5s 保存状态）
- 纪要生成

**需要 desktop 端重写的部分**：
- 权限请求 → 无需（Desktop 无权限系统）
- `AudioCaptureService` 前台服务 → Desktop 无前台服务概念，但要确保窗口关闭时停止录音
- `ExportHelper.shareFile()` → 改为保存文件对话框

**Compose UI 结构**：
```
MeetingScreen
├── TopBar (标题输入 / 计时器 / 结束按钮)
├── ConnectionBanner (连接状态)
├── TranscriptRecyclerView → LazyColumn
│   └── TranscriptItem (说话人标签 + 时间戳 + 文本)
├── BottomBar
│   ├── InterimText (实时出字)
│   ├── StatusIndicator (isSpeaking / isPaused)
│   ├── LanguageSelector (下拉菜单)
│   └── ControlButtons (暂停/重试/开始)
└── SummaryReviewDialog (纪要审核弹窗)
```

### D4. ViewModel 跨平台化

`MeetingViewModel` 当前继承 `AndroidViewModel(application)`，需要改为：

```kotlin
// commonMain
class MeetingViewModel(
    private val engineRouter: EngineRouter,
    private val audioCapture: PlatformAudioCapture,
    private val database: PlatformDatabase,
    private val keyValueStore: PlatformKeyValueStore,
    private val voiceprintModelLoader: VoiceprintModelLoader,
) {
    // 所有原有的 1075 行逻辑，仅替换：
    // getApplication<MeetingApplication>().engineRouter → engineRouter (注入)
    // audioCaptureManager → audioCapture (注入)
    // AppDatabase.getInstance() → database (注入)
    // 其他 Android API 调用 → 替换为 expect/actual
}
```

### D5. 产出物清单（成员 D）

- `desktopApp/src/main/kotlin/com/example/mt/ui/` 下所有 Compose 页面
- `shared/src/commonMain/.../ui/MeetingViewModel.kt` — 跨平台 ViewModel
- `shared/src/commonMain/.../ui/MeetingUiState.kt` — ✅ 直接复用
- `desktopApp/src/main/kotlin/com/example/mt/ui/theme/` — Material 主题

---

## Part E: 构建系统 & 集成打包 (成员 E)

**目标**：Gradle 多模块配置、依赖管理、Windows 打包分���。

### E1. 项目结构搭建

```
meeting-transcriber-desktop/
├── build.gradle.kts              # 根 build
├── settings.gradle.kts            # 包含 shared, desktopApp, androidApp
├── gradle.properties
├── gradle/
│   └── libs.versions.toml        # 版本目录
├── shared/
│   ├── build.gradle.kts           # KMP 配置
│   └── src/
│       ├── commonMain/
│       ├── commonTest/
│       ├── androidMain/
│       ├── androidUnitTest/
│       ├── desktopMain/
│       └── desktopTest/
├── desktopApp/
│   ├── build.gradle.kts           # Compose Desktop 配置
│   └── src/main/
│       ├── kotlin/com/example/mt/Main.kt
│       └── resources/
└── androidApp/
    ├── build.gradle.kts           # 引用 shared + 原 Android UI
    └── src/main/                  # 保留现有 XML/Fragment UI，渐进迁移
```

### E2. Gradle 配置要点

**`gradle/libs.versions.toml`** 关键版本：
```toml
[versions]
kotlin = "2.0.21"
compose-multiplatform = "1.7.3"
sqldelight = "2.0.2"
okhttp = "4.12.0"
coroutines = "1.9.0"
napier = "2.7.1"           # 替代 android.util.Log
kotlinx-serialization = "1.7.3"

[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
napier = { module = "io.github.aakira:napier", version.ref = "napier" }
sqldelight-jvm-driver = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }
# ... etc
```

**`shared/build.gradle.kts`** 核心配置：
```kotlin
kotlin {
    androidTarget()
    jvm("desktop")
    
    sourceSets {
        commonMain.dependencies {
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("io.github.aakira:napier:2.7.1")
            implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
        }
        androidMain.dependencies {
            implementation("com.squareup.okhttp3:okhttp-android:4.12.0") // Android 用 OkHttp 的 Android 引擎
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            implementation("com.k2fsa.sherpa:onnx-android:1.12.29") // sherpa-onnx AAR
        }
        desktopMain.dependencies {
            implementation("com.squareup.okhttp3:okhttp-jvm:4.12.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
            // sherpa-onnx: JNA 加载本地 DLL
            implementation("net.java.dev.jna:jna:5.14.0")
            implementation("app.cash.sqldelight:sqlite-driver:2.0.2") // SQLDelight JVM 驱动
        }
    }
}
```

**`desktopApp/build.gradle.kts`**：
```kotlin
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}

compose.desktop {
    application {
        mainClass = "com.example.mt.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MeetingTranscriber"
            packageVersion = "1.0.0"
            windows {
                menuGroup = "MeetingTranscriber"
                upgradeUuid = "..."
                // 包含 sherpa-onnx DLLs
            }
        }
    }
}
```

### E3. 日志方案

引入 `io.github.aakira:napier:2.7.1` 替代全项目的 `android.util.Log`：
```kotlin
// commonMain
import io.github.aakira.napier.Napier
Napier.i("EngineRouter") { "已解析引擎: ${engine.type.displayName}" }
```

Android actual 桥接到 `android.util.Log`，Desktop actual 桥接到 `println` 或 SLF4J。

### E4. OkHttp engine 选择

OkHttp 4.x 在 KMP 中自带多平台引擎：
- Android → `okhttp-android` (使用 Android 的 `SSLSocketFactory`)
- Desktop/JVM → 默认 `okhttp` (使用 JDK 的 `SSLSocketFactory`)

这是 OkHttp 内置支持，无需额外适配。

### E5. Windows 打包发布

Compose Desktop 的 `nativeDistributions` 插件支持：
- **MSI 安装包** — 适合分发
- **EXE 可执行文件** — 双击即用
- 自动 bundle JRE (通过 `jpackage`)
- 包含 native DLL（sherpa-onnx 的 .dll 文件）

### E6. 产出物清单（成员 E）

- 完整的 Gradle 项目骨架
- 所有 `build.gradle.kts` / `settings.gradle.kts`
- `gradle/libs.versions.toml`
- `desktopApp` 的打包配置 (nativeDistributions)
- CI 脚本 (GitHub Actions) 用于自动构建 Windows MSI
- `README.md` 开发指南

---

## 实施时间线（估算）

| 阶段 | 周次 | 成员 A | 成员 B | 成员 C | 成员 D | 成员 E |
|------|------|--------|--------|--------|--------|--------|
| 搭建 | 1 | expect 声明 + Logger | 研究 Java Sound API | 研究 SQLDelight | 学习 Compose Desktop | Gradle 骨架搭建 |
| 核心 | 2-3 | 引擎层全部迁移 | 实现 DesktopAudioCapture | SQLDelight 表定义 | 首页 + 设置页面 | CI + 版本目录 |
| 功能 | 3-5 | domain + network 迁移 | WAV录音+播放+声纹DLL | Repository + 加密存储 | **MeetingScreen** | 打包配置调通 |
| 集成 | 5-6 | bug 修复 + 测试 | 音频质量调优 | DB 测试 | UI 完善 + 弹窗 | MSI 打包 + 测试 |
| 打磨 | 6-7 | 代码 review | 声纹精度验证 | 性能优化 | 暗色主题+动画 | 签名 + 发布 |

---

## 技术风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| Java Sound 在某些声卡上不工作 | 中 | B | 备选 JNA + WASAPI |
| sherpa-onnx Windows JNI 接口与 Android 不兼容 | 低 | B | 提前在 `VoiceprintIdentifier` 中抽象模型加载器（expect/actual） |
| Compose Desktop 复杂列表性能（实时转写逐句插入） | 中 | D | LazyColumn + key + 限制 item 动画，与 Android RecyclerView 做法一致 |
| SQLDelight 迁移后查询性能下降 | 低 | C | SQLDelight 生成的代码与 Room 性能相当，都是 SQLite 底层 |
| okhttp 在 Desktop SSL 握手问题 | 低 | E | okhttp 4.x 在 JVM 上默认用 JDK SSLEngine，稳定多年 |

---

## 验证计划

### 单元测试
- `commonTest`: 引擎路由逻辑、PromptBuilder、TextFormatter、VADDetector
- `desktopTest`: AudioCapture、SQLDelight DAOs

### 集成测试
1. 录制一段音频 → WAV 文件完整性检查（字节数 = 时长 × 32000 bytes/s）
2. 用已知文本的音频 → ASR 输出对比
3. 转写结果 → LLM 摘要生成
4. 崩溃恢复 — kill 进程后重新打开，验证恢复

### 端到端验证
1. 在 Windows 上录制 10 分钟模拟会议
2. 检查实时转写正确性
3. 结束后生成会议纪要
4. 历史记录可回放、可编辑
5. 纪要导出为 TXT 文件
