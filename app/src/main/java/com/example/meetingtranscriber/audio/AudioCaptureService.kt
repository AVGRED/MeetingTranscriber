package com.example.meetingtranscriber.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.meetingtranscriber.MainActivity
import com.example.meetingtranscriber.MeetingApplication
import com.example.meetingtranscriber.R

class AudioCaptureService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.meetingtranscriber.ACTION_STOP_MEETING"
        const val FOREGROUND_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isSilent = getSharedPreferences("meeting_prefs", MODE_PRIVATE)
            .getBoolean("background_silent", false)
        val priority = if (isSilent) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW

        val notification = NotificationCompat.Builder(this, MeetingApplication.CHANNEL_MEETING)
            .setContentTitle("会议转写进行中")
            .setContentText("正在实时转写会议内容...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "结束会议", stopIntent)
            .setOngoing(true)
            .setPriority(priority)
            .build()

        try {
            startForeground(FOREGROUND_ID, notification)
        } catch (e: SecurityException) {
            Log.e("AudioCaptureService", "缺少前台服务权限: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.e("AudioCaptureService", "后台不允许启动前台服务: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
}
