package com.example.meetingtranscriber

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.example.meetingtranscriber.audio.AudioCacheManager
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.db.RecoveryStateEntity
import com.example.meetingtranscriber.engine.EngineRouter
import com.example.meetingtranscriber.engine.asr.*
import com.example.meetingtranscriber.engine.llm.*
import com.example.meetingtranscriber.network.UpdateChecker
import com.example.meetingtranscriber.security.CryptoManager
import com.example.meetingtranscriber.util.StorageMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MeetingApplication : Application() {

    companion object {
        const val CHANNEL_MEETING = "meeting_recording"
        lateinit var instance: MeetingApplication
            private set
        var pendingRecoveryState: RecoveryStateEntity? = null
        /** 恢复状态检查完成信号（检查已移后台，MainActivity 等它完成再读结果） */
        val recoveryCheckDone = CompletableDeferred<Unit>()
    }

    /** 本地 ASR 引擎（模型常驻，跨会议复用；启动时后台预加载） */
    private val funAsrLocal by lazy { FunAsrEngine(this) }

    /** 引擎路由器（懒加载单例） */
    val engineRouter: EngineRouter by lazy {
        val prefs = PreferencesManager(this)
        EngineRouter(
            prefs = prefs,
            funAsrEngine = funAsrLocal,
            funAsrCloudEngine = FunAsrCloudEngine(prefs),
            tingwuEngine = TingwuEngine(prefs),
            volcengineEngine = VolcengineEngine(prefs),
            qwenEngine = QwenEngine(this),
            doubaoEngine = DoubaoEngine(prefs),
            dashScopeEngine = DashScopeEngine(prefs)
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()

        // 启动重活全部移出主线程：Keystore/EncryptedPrefs 初始化 + SQLCipher 打开/迁移
        // + 文件清理是低端机冷启动白屏元凶（消费方经 CryptoManager.awaitInitialized 就绪门等待）
        CoroutineScope(Dispatchers.IO).launch {
            CryptoManager.init(this@MeetingApplication)
            val prefs = PreferencesManager(this@MeetingApplication)
            prefs.warmUp() // 预热 EncryptedSharedPreferences，避免主线程首触付出 Keystore 成本
            checkRecoveryState()
            recoveryCheckDone.complete(Unit)
            // 默认本地引擎时预加载 ASR 模型（226MB 需 3-4s）：首次开会免等
            if (prefs.preferredAsrEngine ==
                com.example.meetingtranscriber.engine.AsrEngineType.FUNASR_LOCAL) {
                funAsrLocal.initialize(this@MeetingApplication)
            }
            cleanupExpiredRecordings()
            AudioCacheManager.cleanup(this@MeetingApplication)
            StorageMonitor.maybeNotify(this@MeetingApplication)
            val info = UpdateChecker.check(this@MeetingApplication)
            if (info != null) {
                UpdateChecker.maybeNotify(this@MeetingApplication, info)
            }
        }
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CHANNEL_MEETING,
            "会议录音",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "会议录音进行中的通知"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private suspend fun checkRecoveryState() {
        try {
            val db = AppDatabase.getInstance(this)
            val state = db.recoveryStateDao().getLatest()
            if (state != null) {
                val ageMs = System.currentTimeMillis() - state.lastSavedAt
                if (ageMs < 24 * 3600 * 1000L) {
                    pendingRecoveryState = state
                    Log.i("MeetingApplication", "发现待恢复的会议: ${state.title}")
                } else {
                    db.recoveryStateDao().deleteAll()
                    Log.i("MeetingApplication", "恢复状态已过期，已清除")
                }
            }
        } catch (e: Exception) {
            Log.w("MeetingApplication", "检查恢复状态失败: ${e.message}")
        }
    }

    private fun cleanupExpiredRecordings() {
        val recordingsDir = getExternalFilesDir("realtime_recordings") ?: return
        val thirtyDaysMs = 30L * 24 * 3600 * 1000
        val cutoff = System.currentTimeMillis() - thirtyDaysMs
        recordingsDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
                Log.i("MeetingApplication", "已清理过期录音: ${file.name}")
            }
        }
    }
}
