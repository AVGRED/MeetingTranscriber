package com.example.meetingtranscriber.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.meetingtranscriber.MainActivity
import java.io.File

object StorageMonitor {

    private const val CHANNEL_STORAGE = "storage_warning"
    private const val NOTIFY_ID = 2001
    private const val THRESHOLD_WARN_MB = 100L
    private const val THRESHOLD_CRITICAL_MB = 50L

    fun check(context: Context): StorageStatus {
        val internalFree = context.filesDir.usableSpace / (1024 * 1024)
        val externalFree = context.getExternalFilesDir(null)?.usableSpace?.div(1024 * 1024) ?: internalFree
        val recordingsSize = getRecordingsSize(context)

        return StorageStatus(
            internalFreeMb = internalFree,
            externalFreeMb = externalFree,
            recordingsSizeMb = recordingsSize,
            isCritical = minOf(internalFree, externalFree) < THRESHOLD_CRITICAL_MB,
            isWarning = minOf(internalFree, externalFree) < THRESHOLD_WARN_MB
        )
    }

    fun maybeNotify(context: Context) {
        val status = check(context)
        createChannel(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (status.isCritical) {
            val intent = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_STORAGE)
                .setContentTitle("存储空间不足")
                .setContentText("可用空间仅 ${status.minFreeMb}MB，建议清理旧录音")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFY_ID, notification)
        } else {
            manager.cancel(NOTIFY_ID)
        }
    }

    fun cleanupOldRecordings(context: Context): Long {
        var freedBytes = 0L
        val recordingsDir = context.getExternalFilesDir("realtime_recordings") ?: return 0
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        recordingsDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                freedBytes += file.length()
                file.delete()
            }
        }
        return freedBytes
    }

    fun getRecordingsSize(context: Context): Long {
        var total = 0L
        context.getExternalFilesDir("realtime_recordings")?.listFiles()?.forEach {
            total += it.length()
        }
        return total / (1024 * 1024)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_STORAGE,
                "存储空间提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { setShowBadge(false) }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

data class StorageStatus(
    val internalFreeMb: Long,
    val externalFreeMb: Long,
    val recordingsSizeMb: Long,
    val isCritical: Boolean,
    val isWarning: Boolean
) {
    val minFreeMb: Long get() = minOf(internalFreeMb, externalFreeMb)
}
