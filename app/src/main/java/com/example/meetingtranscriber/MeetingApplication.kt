package com.example.meetingtranscriber

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.db.RecoveryStateEntity
import com.example.meetingtranscriber.network.UpdateChecker
import com.example.meetingtranscriber.security.CryptoManager
import com.example.meetingtranscriber.util.StorageMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MeetingApplication : Application() {

    companion object {
        const val CHANNEL_MEETING = "meeting_recording"
        lateinit var instance: MeetingApplication
            private set
        var pendingRecoveryState: RecoveryStateEntity? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CryptoManager.init(this)
        createNotificationChannels()
        checkRecoveryState()
        cleanupExpiredRecordings()
        StorageMonitor.maybeNotify(this)

        CoroutineScope(Dispatchers.IO).launch {
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

    private fun checkRecoveryState() {
        try {
            val db = AppDatabase.getInstance(this)
            val state = runBlocking {
                db.recoveryStateDao().getLatest()
            }
            if (state != null) {
                val ageMs = System.currentTimeMillis() - state.lastSavedAt
                if (ageMs < 24 * 3600 * 1000L) {
                    pendingRecoveryState = state
                    Log.i("MeetingApplication", "发现待恢复的会议: ${state.title}")
                } else {
                    runBlocking { db.recoveryStateDao().deleteAll() }
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
