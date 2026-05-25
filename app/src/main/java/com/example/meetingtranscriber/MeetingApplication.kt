package com.example.meetingtranscriber

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MeetingApplication : Application() {

    companion object {
        const val CHANNEL_MEETING = "meeting_recording"
        lateinit var instance: MeetingApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
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
}
