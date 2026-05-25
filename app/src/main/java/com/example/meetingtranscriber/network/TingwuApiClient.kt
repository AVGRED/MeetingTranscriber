package com.example.meetingtranscriber.network

import android.util.Log
import com.example.meetingtranscriber.BuildConfig
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通义听悟 REST API 客户端
 *
 * 负责：
 * 1. 创建实时转写任务 → 获取 WebSocket URL
 * 2. 停止转写任务
 * 3. 获取转写结果（可选，用于会议结束后拉取完整结果）
 *
 * API 文档: https://help.aliyun.com/document_detail/tingwu.html
 */
class TingwuApiClient {

    companion object {
        private const val TAG = "TingwuApiClient"
        // 通义听悟 API 地址
        private const val API_HOST = "tingwu.cn-beijing.aliyuncs.com"
        private const val API_BASE = "https://$API_HOST/openapi/tingwu/v2"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 创建实时转写任务
     * @param appKey 通义听悟 AppKey
     * @param accessToken 阿里云 RAM 鉴权 Token (通过 AccessKey 生成)
     * @return CreateTaskResult (taskId + meetingJoinUrl)
     */
    suspend fun createRealtimeTask(
        appKey: String = BuildConfig.ALIYUN_TINGWU_APP_KEY,
        accessToken: String
    ): CreateTaskResult? = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("AppKey", appKey)
                put("Type", "realtime")

                put("Input", JSONObject().apply {
                    put("SourceLanguage", "cn")
                    put("Format", "pcm")
                    put("SampleRate", 16000)
                    put("TaskKey", "meeting-${System.currentTimeMillis()}")
                })

                put("Parameters", JSONObject().apply {
                    // 转写参数
                    put("Transcription", JSONObject().apply {
                        put("OutputLevel", 2)            // 词级别

                        // 说话人分离
                        put("DiarizationEnabled", true)
                        put("Diarization", JSONObject().apply {
                            put("SpeakerCount", 0)        // 0 = 自动检测
                        })
                    })

                    // 自动章节（可选）
                    put("AutoChaptersEnabled", false)
                })
            }

            val request = Request.Builder()
                .url("$API_BASE/tasks")
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            Log.d(TAG, "CreateTask 响应: $responseBody")

            if (!response.isSuccessful) {
                Log.e(TAG, "创建任务失败: HTTP ${response.code}, $responseBody")
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val data = json.optJSONObject("Data") ?: return@withContext null

            CreateTaskResult(
                taskId = data.optString("TaskId", ""),
                taskKey = data.optString("TaskKey", ""),
                meetingJoinUrl = data.optString("MeetingJoinUrl", "")
            )
        } catch (e: IOException) {
            Log.e(TAG, "创建任务网络错误: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "创建任务异常: ${e.message}")
            null
        }
    }

    /**
     * 停止实时转写任务
     */
    suspend fun stopTask(
        taskId: String,
        accessToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_BASE/tasks/$taskId/stop")
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "停止任务失败: ${e.message}")
            false
        }
    }

    /**
     * 获取任务结果（会议结束后获取完整转写文本，包含云端后处理结果）
     */
    suspend fun getTaskResult(
        taskId: String,
        accessToken: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_BASE/tasks/$taskId/transcription")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            response.body?.string()
        } catch (e: Exception) {
            Log.e(TAG, "获取任务结果失败: ${e.message}")
            null
        }
    }
}

data class CreateTaskResult(
    val taskId: String,
    val taskKey: String,
    val meetingJoinUrl: String   // WebSocket 连接地址
) {
    /** 是否有效 */
    val isValid: Boolean
        get() = taskId.isNotBlank() && meetingJoinUrl.isNotBlank()
}
