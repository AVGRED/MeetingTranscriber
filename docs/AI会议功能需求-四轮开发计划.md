# AI会议功能需求 — 完整四轮开发计划

> 基于需求清单 30 项，对照现有代码完成度分析
> 日期：2026-06-13
> 当前状态：P0 已完成，P1-P3 待执行

---

## 需求完成度总览

```
需求总数：30 项
✅ 已完成：16 项 (53%)  ← P0 完成后
⚠️ 部分完成：8 项 (27%)
❌ 缺失：6 项 (20%)
```

---

# 第一轮：安全与数据保护（P0）✅ 已完成

> 执行日期：2026-06-13
> 详细方案见 `docs/P0-安全与数据保护-方案与实施.md`

| # | 功能 | 状态 | 涉及文件 |
|---|------|------|---------|
| 1.1 | Room 数据库加密（SQLCipher） | ✅ | AppDatabase.kt, build.gradle.kts |
| 1.2 | WAV 音频文件加密（EncryptedFile） | ✅ | WavRecorder.kt, WavPlayer.kt |
| 1.3 | 密钥管理体系（AndroidKeyStore） | ✅ | security/CryptoManager.kt |
| 2.1 | 5 秒定时保存会议状态 | ✅ | MeetingViewModel.kt, RecoveryStateDao.kt |
| 2.2 | 冷启动崩溃检测 + 恢复弹窗 | ✅ | MeetingApplication.kt, MainActivity.kt |
| 2.3 | onCleared 最后防线保存 | ✅ | MeetingViewModel.kt |
| 3.1 | 音频缓冲扩容 20s→120s | ✅ | AsrWebSocketClient.kt |
| 3.2 | 内存溢出到磁盘 | ✅ | AsrWebSocketClient.kt |
| 3.3 | 恢复时回放溢出音频 | ✅ | MeetingViewModel.kt |

**产出**：新增 3 文件，修改 10 文件，编译通过零错误。

---

# 第二轮：核心功能补全（P1）

> 预估：6.5 工作日
> 状态：待执行

### 2.1 多语种/方言选择

**对应需求**：一-1（方言识别+语种识别）、一-4（混合/多语种识别）

**现状**：`TingwuApiClient.createRealtimeTask()` 中 `SourceLanguage` 硬编码 `"cn"`

**方案**：
1. `MeetingFragment` 布局新增语种选择下拉框（会议标题旁或 Dialog）
2. 选项映射：

| UI 选项 | API 参数 |
|---------|---------|
| 普通话 | `cn` |
| 粤语 | `yue` |
| 英语 | `en` |
| 中英混合 | `cn-en` |
| 自动检测 | `auto` |

3. `MeetingViewModel.startMeeting()` 接受 `language: String` 参数
4. `TingwuApiClient.createRealtimeTask()` 接受 `sourceLanguage` 参数
5. `MeetingUiState` 新增 `selectedLanguage: String` 字段

**涉及文件**：
- `ui/meeting/MeetingFragment.kt` — UI 下拉框
- `ui/meeting/MeetingViewModel.kt` — 传参
- `network/TingwuApiClient.kt` — API 参数
- `res/layout/fragment_meeting.xml` — 布局

**风险**：阿里云 ASR 对不同语种的支持程度不同，需查阅文档确认 `SourceLanguage` 参数取值。

---

### 2.2 自定义词库导入

**对应需求**：二-6（企业自定义词库/导入专业词汇）

**现状**：无词库管理，API 中未传 `VocabularyId`

**方案**：
1. 新建 UI 入口：`SettingsFragment`（设置页面，底部导航第三 Tab 或详情页入口）
2. 词库管理功能：
   - 从本地 txt/csv 文件导入词汇列表
   - 支持手动添加/编辑词汇
   - 存储到 Room 新表 `vocabulary`
3. 通义听悟 API 集成：
   - 先调用阿里云 CreateVocabulary API 上传词库
   - 获取 `VocabularyId`
   - 在 `createRealtimeTask()` 的 `Parameters` 中传入 `VocabularyId`
4. 词库与会议关联（不同会议可用不同词库）

**新增文件**：
- `ui/settings/SettingsFragment.kt`
- `ui/settings/SettingsViewModel.kt`
- `data/db/VocabularyEntity.kt`
- `data/db/VocabularyDao.kt`
- `network/VocabularyApiClient.kt`

**涉及文件**：
- `AppDatabase.kt` — version 4 migration
- `network/TingwuApiClient.kt` — 传入 VocabularyId
- `MeetingViewModel.kt` — 词库选择
- `res/layout/fragment_settings.xml`
- `res/menu/bottom_navigation.xml`

**风险**：阿里云 CreateVocabulary API 有调用频率和词汇数量限制，需查阅文档。

---

### 2.3 实时模式音频存档

**对应需求**：四-1（音频与转写稿分开存储，本地管理）

**现状**：仅离线模式保存 WAV；实时模式音频不存档

**方案**：
1. `startMeeting()` 中同步启动 `WavRecorder`（与音频采集并行）
2. 音频同时流向：ASR WebSocket + 本地 WAV 文件
3. 结束会议时停止 WavRecorder，路径存入 Room
4. 过期清理策略：超过 30 天的音频自动删除（可配置）

**涉及文件**：
- `ui/meeting/MeetingViewModel.kt` — startMeeting/endMeeting 流程
- `data/repository/MeetingRepository.kt` — 清理方法
- `MeetingApplication.kt` — 启动时检查过期文件

**工作量**：1 天，改动集中在一个文件。

---

### 2.4 数字/标点一键纠正 UI

**对应需求**：二-4（数字/标点一键纠正）

**现状**：`TextFormatter.spokenNumberToDigits()` 和 `format()` 已实现，但缺 UI 触发

**方案**：
1. 详情页 `DetailFragment` 工具栏新增"文本规整"按钮
2. 点击后对所有 segments 应用 `TextFormatter.spokenNumberToDigits()` + `TextFormatter.format()`
3. 更新 DB 并刷新 UI
4. 支持撤销（保存原始文本）

**涉及文件**：
- `ui/detail/DetailFragment.kt` — 按钮 + 逻辑
- `ui/detail/DetailViewModel.kt` — 规整方法
- `res/layout/fragment_detail.xml` — 布局

**工作量**：0.5 天

---

### 2.5 纪要在线编辑

**对应需求**：三-4（纪要文档支持在线二次编辑）

**现状**：纪要只读展示在 `tv_summary` TextView

**方案**：
1. 详情页纪要区域改为 `EditText`（点击进入编辑模式）
2. 保存按钮 → 更新 Room `meetings.summary`
3. 重新生成按钮保留（覆盖编辑内容前确认）
4. 支持撤销到 LLM 生成的原始纪要

**涉及文件**：
- `ui/detail/DetailFragment.kt` — 编辑模式切换
- `ui/detail/DetailViewModel.kt` — 保存纪要
- `res/layout/fragment_detail.xml` — TextView → EditText

**工作量**：1 天

---

### 2.6 话题分段优化

**对应需求**：二-5（原文分段排版优化）

**现状**：转写按句子逐一展示，无话题/章节概念

**方案**：
1. 基于时间间隔自动分段：两个连续句子之间静音 > 30s → 新话题
2. 可选 LLM 分段：将全文发给通义千问，请求输出话题分段
3. UI 渲染：RecyclerView 插入话题分隔符 Header
4. 导出时保留章节结构

**涉及文件**：
- `ui/meeting/MeetingSummaryGenerator.kt` — LLM 分段 prompt
- `ui/meeting/TranscriptAdapter.kt` — 新增 VIEW_TYPE_TOPIC_HEADER
- `data/model/TranscriptSegment.kt` — 可选 topicId 字段
- `data/db/TranscriptEntity.kt` — topicId 列

**风险**：LLM 分段可能不准确，需要 fallback 到时间间隔方案。

---

# 第三轮：体验打磨（P2）

> 预估：6 工作日

### 3.1 软删除 + 回收站

**对应需求**：四-3（历史会议存档/删除/恢复）

**现状**：硬删除（`DELETE FROM meetings WHERE id = :meetingId`），不可恢复

**方案**：
1. `MeetingEntity` 新增 `isArchived: Boolean` 和 `archivedAt: Long?` 字段
2. 删除操作改为设置 `isArchived = true`
3. 历史列表默认只显示 `isArchived = false`
4. 新增回收站入口 → 显示已归档会议 → 支持恢复/彻底删除
5. AppDatabase version 4 migration

**涉及文件**：
- `data/db/MeetingEntity.kt` — 新字段
- `data/db/MeetingDao.kt` — 新查询方法
- `data/db/AppDatabase.kt` — version 4 migration
- `ui/history/HistoryFragment.kt` — 回收站入口
- `data/repository/MeetingRepository.kt` — archive/restore 方法

**工作量**：1 天

---

### 3.2 后台常驻开关

**对应需求**：六-5（可选后台常驻运行）

**现状**：前台 Service 始终运行，通知栏有常驻通知

**方案**：
1. 设置页新增"后台静默运行"开关（默认关闭）
2. 关闭时：前台通知正常显示
3. 开启时：降低通知优先级为 MIN，或使用 `setTimeoutAfter()` 让系统自动清理
4. 需要额外处理 Android 14+ 后台限制

**涉及文件**：
- `audio/AudioCaptureService.kt` — 模式切换
- `ui/settings/SettingsFragment.kt` — 开关 UI

**工作量**：1 天

---

### 3.3 应用图标

**对应需求**：无明确对应，但 UI 打磨需要

**现状**：使用 Android 默认图标

**方案**：使用 Android Studio Image Asset 工具生成自适应图标（前景：麦克风+文字气泡，背景：品牌色）

**涉及文件**：
- `res/mipmap-*/ic_launcher.png`
- `res/mipmap-anydpi-v26/ic_launcher.xml`

**工作量**：0.5 天

---

### 3.4 文本转写搜索高亮

**对应需求**：四-3（关键词搜索）

**现状**：搜索已实现但搜索结果中关键词不高亮

**方案**：
1. `TranscriptAdapter` 新增搜索结果模式，使用 `SpannableString` 高亮匹配文本
2. 搜索结果按匹配位置排序（标题匹配 > 内容匹配）

**涉及文件**：
- `ui/meeting/TranscriptAdapter.kt` — 高亮渲染

**工作量**：0.5 天

---

### 3.5 会议标签与分类

**对应需求**：四-3（历史会议管理增强）

**现状**：会议仅按时间排序，无分类

**方案**：
1. 预设标签：产品/技术/客户/人事/其他
2. 创建会议时可选标签
3. 历史列表支持按标签筛选
4. `MeetingEntity` 新增 `tag: String?` 字段

**涉及文件**：
- `data/db/MeetingEntity.kt` — tag 字段
- `ui/meeting/MeetingFragment.kt` — 标签选择
- `ui/history/HistoryFragment.kt` — 标签筛选

**工作量**：1.5 天

---

### 3.6 离线录音后自动上传提示

**对应需求**：优化离线转在线流程

**现状**：离线录音后用户需手动在历史列表点击上传

**方案**：
1. 结束离线会议后弹窗"是否立即上传到云端转写？"
2. 确认后自动跳转到上传流程

**涉及文件**：
- `ui/meeting/MeetingFragment.kt` — 弹窗
- `ui/meeting/MeetingViewModel.kt` — 流程衔接

**工作量**：0.5 天

---

# 第四轮：云端与运维（P3）

> 预估：7 工作日

### 4.1 可选云同步

**对应需求**：四-2（可选云同步）

**方案**：
1. 使用阿里云 OSS 作为存储后端
2. 同步内容：
   - 会议元数据（JSON）
   - 转写文本（JSON）
   - 音频文件（可选，通常不自动同步）
   - 纪要文档
3. 同步策略：
   - 手动触发（非自动同步，避免流量消耗）
   - 增量同步（基于 `updatedAt` 时间戳）
   - 冲突处理：本地优先，云端为备份
4. 恢复：新设备登录后可从云端拉取历史会议

**新增文件**：
- `network/CloudSyncManager.kt`
- `network/OssClient.kt`
- `data/db/SyncStateEntity.kt`

**涉及文件**：
- `MeetingApplication.kt` — 同步服务初始化
- `ui/history/HistoryFragment.kt` — 同步按钮
- `ui/settings/SettingsFragment.kt` — 同步设置

**风险**：
- OSS 有存储和流量费用
- 需要处理网络错误和部分同步
- 用户隐私：音频文件应默认不同步

---

### 4.2 应用内更新检查

**对应需求**：六-2（自动更新）

**方案**：
1. 自建版本检查 API 或使用 GitHub Releases API
2. 启动时检查最新版本号
3. `versionCode` 低于最新版 → 通知栏提示 + 设置页红点
4. 下载 APK → `FileProvider` + `Intent.ACTION_VIEW` 触发安装
5. 更新日志展示（Markdown 渲染）

**新增文件**：
- `network/UpdateChecker.kt`

**涉及文件**：
- `MeetingApplication.kt` — 启动时检查
- `ui/settings/SettingsFragment.kt` — 手动检查入口

**工作量**：1.5 天

---

### 4.3 存储空间提醒

**对应需求**：六-6（存储空间异常提醒）

**方案**：
1. 监控 `context.filesDir` 和 `context.getExternalFilesDir(null)` 可用空间
2. 低于 100MB → 通知栏提示
3. 低于 50MB → 弹窗警告，建议清理旧音频
4. 提供一键清理：删除 >30 天的未归档录音

**涉及文件**：
- `util/StorageMonitor.kt` — 新文件
- `MeetingApplication.kt` — 启动监控
- `ui/settings/SettingsFragment.kt` — 手动检查

**工作量**：0.5 天

---

### 4.4 单元测试

**对应需求**：质量保障

**方案**：
1. `TextFormatterTest` 扩展：边界值、大数字、混合文本
2. `VADDetectorTest` 扩展：纯静音、噪声环境、长段语音
3. 新增 `MeetingViewModelTest`：状态转换测试
4. 新增 `CryptoManagerTest`：加密/解密往返测试
5. Room DAO 集成测试

**工作量**：2 天

---

# 工作量汇总

| 轮次 | 内容 | 工作日 | 累计 |
|------|------|--------|------|
| P0 | 安全与数据保护 | 4d ✅ | 4d |
| P1 | 核心功能补全（6 项） | 6.5d | 10.5d |
| P2 | 体验打磨（6 项） | 6d | 16.5d |
| P3 | 云端与运维（4 项） | 7d | 23.5d |

**总计**：约 24 个工作日从当前状态到 v1.0 交付。

---

# 需求覆盖矩阵

| 需求编号 | 需求 | 覆盖轮次 | 状态 |
|---------|------|---------|------|
| 一-1 | 方言识别+语种识别 | P1-2.1 | 待开发 |
| 一-2 | 说话人识别 | - | ✅ 已实现 |
| 一-3 | 识别速度 1s 内 | - | ⚠️ 云端依赖 |
| 一-4 | 混合/多语种识别 | P1-2.1 | 待开发 |
| 一-5 | 说话人标注 | - | ✅ 已实现 |
| 一-6 | 暂停/中断控制 | - | ✅ 已实现 |
| 二-1 | 准确率 98%+ | - | ⚠️ 云端依赖 |
| 二-2 | 转写速度 2s 内 | - | ⚠️ 云端依赖 |
| 二-3 | 实时流式预览 | - | ✅ 已实现 |
| 二-4 | 数字/标点一键纠正 | P1-2.4 | 待开发 |
| 二-5 | 原文分段排版 | P1-2.6 | 待开发 |
| 二-6 | 企业自定义词库 | P1-2.2 | 待开发 |
| 三-1 | 总结全文 | - | ✅ 已实现 |
| 三-2 | 总结要点/精简摘要 | - | ✅ 已实现 |
| 三-3 | PDF/Word/TXT 导出 | - | ✅ 已实现 |
| 三-4 | 纪要在线编辑 | P1-2.5 | 待开发 |
| 四-1 | 音频/转写分开存储 | P1-2.3 | 待开发 |
| 四-2 | 可选云同步 | P3-4.1 | 待开发 |
| 四-3 | 历史存档/搜索/删除/恢复 | P2-3.1 | 待开发 |
| 四-4 | 本地存储加密 | P0 | ✅ 已完成 |
| 五-1 | 暗色主题 | - | ✅ 已实现 |
| 五-2 | 操作简单直观 | - | ✅ 已实现 |
| 五-3 | 核心功能一键可达 | - | ✅ 已实现 |
| 五-4 | 响应式自适应 | - | ✅ 已实现 |
| 六-1 | Android 12+ | - | ✅ 已实现 |
| 六-2 | 自动更新 | P3-4.2 | 待开发 |
| 六-3 | 断点续传 | P0 | ✅ 已完成 |
| 六-4 | 异常保护数据 | P0 | ✅ 已完成 |
| 六-5 | 后台常驻可选 | P2-3.2 | 待开发 |
| 六-6 | 存储空间提醒 | P3-4.3 | 待开发 |
