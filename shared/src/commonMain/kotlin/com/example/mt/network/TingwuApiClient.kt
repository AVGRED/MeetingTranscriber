package com.example.mt.network

import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * 通义听悟 REST API 客户端（跨平台，无 Android 依赖）。
 *
 * 负责：
 * 1. 创建实时转写任务 → 获取 WebSocket URL
 * 2. 停止转写任务
 * 3. 获取转写结果
 */
class TingwuApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun createRealtimeTask(
        appKey: String,
        accessKeyId: String,
        accessKeySecret: String,
        sourceLanguage: String = "cn",
        vocabularyId: String? = null,
    ): CreateTaskResult? = withContext(Dispatchers.Default) {
        try {
            val bodyJson = JSONObject().apply {
                put("AppKey", appKey)
                put("Operation", "start")
                put("Input", JSONObject().apply {
                    put("SourceLanguage", sourceLanguage)
                    put("Format", "pcm")
                    put("SampleRate", 16000)
                    put("TaskKey", "meeting-${System.currentTimeMillis()}")
                })
                put("Parameters", JSONObject().apply {
                    put("Transcription", JSONObject().apply {
                        put("OutputLevel", 2)
                        put("DiarizationEnabled", true)
                        put("Diarization", JSONObject().apply {
                            put("SpeakerCount", 0)
                        })
                        if (!vocabularyId.isNullOrBlank()) {
                            put("VocabularyId", vocabularyId)
                        }
                    })
                    put("AutoChaptersEnabled", false)
                })
            }

            val query = "type=realtime"
            val path = "/openapi/tingwu/v2/tasks"
            val date = rfc1123Date()
            val bodyStr = bodyJson.toString()
            val signature = AliyunSigner.sign(accessKeyId, accessKeySecret, "PUT", path, query, bodyStr, date)

            val request = Request.Builder()
                .url("$API_BASE/tasks?$query")
                .header("Authorization", signature)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Date", date)
                .header("Host", API_HOST)
                .put(bodyStr.toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            Napier.d("$TAG: CreateTask 响应: $responseBody")

            if (!response.isSuccessful) {
                Napier.e("$TAG: 创建任务失败: HTTP ${response.code}, $responseBody")
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val data = json.optJSONObject("Data") ?: return@withContext null

            CreateTaskResult(
                taskId = data.optString("TaskId", ""),
                taskKey = data.optString("TaskKey", ""),
                meetingJoinUrl = data.optString("MeetingJoinUrl", ""),
            )
        } catch (e: IOException) {
            Napier.e("$TAG: 创建任务网络错误: ${e.message}")
            null
        } catch (e: Exception) {
            Napier.e("$TAG: 创建任务异常: ${e.message}")
            null
        }
    }

    suspend fun stopTask(
        taskId: String,
        accessKeyId: String,
        accessKeySecret: String,
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            val bodyJson = JSONObject().apply {
                put("TaskId", taskId)
                put("Operation", "stop")
            }
            val path = "/openapi/tingwu/v2/tasks"
            val date = rfc1123Date()
            val bodyStr = bodyJson.toString()
            val signature = AliyunSigner.sign(accessKeyId, accessKeySecret, "PUT", path, "", bodyStr, date)

            val request = Request.Builder()
                .url("$API_BASE/tasks")
                .header("Authorization", signature)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Date", date)
                .header("Host", API_HOST)
                .put(bodyStr.toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            Napier.e("$TAG: 停止任务失败: ${e.message}")
            false
        }
    }

    suspend fun getTaskResult(
        taskId: String,
        accessKeyId: String,
        accessKeySecret: String,
    ): String? = withContext(Dispatchers.Default) {
        try {
            val path = "/openapi/tingwu/v2/tasks/$taskId/transcription"
            val date = rfc1123Date()
            val signature = AliyunSigner.sign(accessKeyId, accessKeySecret, "GET", path, "", "", date)

            val request = Request.Builder()
                .url("$API_BASE/tasks/$taskId/transcription")
                .header("Authorization", signature)
                .header("Accept", "application/json")
                .header("Date", date)
                .header("Host", API_HOST)
                .get()
                .build()

            client.newCall(request).execute().body?.string()
        } catch (e: Exception) {
            Napier.e("$TAG: 获取任务结果失败: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "TingwuApiClient"
        private const val API_HOST = "tingwu.cn-beijing.aliyuncs.com"
        private const val API_BASE = "https://$API_HOST/openapi/tingwu/v2"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        // 线程安全的 SimpleDateFormat 缓存，避免每次调用创建新实例
        private val dateFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
        }

        private fun rfc1123Date(): String = dateFormat.get().format(Date())
    }
}

data class CreateTaskResult(
    val taskId: String,
    val taskKey: String,
    val meetingJoinUrl: String,
) {
    val isValid: Boolean get() = taskId.isNotBlank() && meetingJoinUrl.isNotBlank()
}
