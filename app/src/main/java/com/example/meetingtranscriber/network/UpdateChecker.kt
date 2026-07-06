package com.example.meetingtranscriber.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.meetingtranscriber.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val CHANNEL_UPDATE = "app_update"
    private const val NOTIFY_ID = 2002

    // 默认使用 GitHub Releases API，可在设置中自定义
    private const val DEFAULT_UPDATE_URL =
        "https://api.github.com/repos/user/meeting-transcriber/releases/latest"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val changelog: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(context: Context, url: String = getUpdateUrl(context)): UpdateInfo? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                parseGitHubRelease(body)
            } catch (e: Exception) {
                Log.w(TAG, "更新检查失败: ${e.message}")
                null
            }
        }

    fun maybeNotify(context: Context, info: UpdateInfo) {
        val currentCode = getCurrentVersionCode(context)
        if (info.versionCode <= currentCode) return

        createChannel(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATE)
            .setContentTitle("发现新版本 ${info.versionName}")
            .setContentText("点击查看更新内容")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFY_ID, notification)
    }

    suspend fun downloadApk(context: Context, info: UpdateInfo): File? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(info.downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val file = File(context.cacheDir, "update_${info.versionCode}.apk")
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                file
            } catch (e: Exception) {
                Log.e(TAG, "APK下载失败: ${e.message}")
                null
            }
        }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionCode
        } catch (e: Exception) {
            0
        }
    }

    private fun getUpdateUrl(context: Context): String {
        return context.getSharedPreferences("meeting_prefs", Context.MODE_PRIVATE)
            .getString("update_url", DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
    }

    private fun parseGitHubRelease(json: String): UpdateInfo? {
        val obj = JSONObject(json)
        val tagName = obj.optString("tag_name", "0")
        val versionCode = tagName.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        val apkAsset = obj.optJSONArray("assets")?.let { assets ->
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name", "").endsWith(".apk")) {
                    return@let asset.optString("browser_download_url", "")
                }
            }
            ""
        } ?: ""
        return UpdateInfo(
            versionCode = versionCode,
            versionName = obj.optString("name", tagName),
            downloadUrl = apkAsset,
            changelog = obj.optString("body", "")
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_UPDATE,
                "应用更新",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setShowBadge(false) }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
