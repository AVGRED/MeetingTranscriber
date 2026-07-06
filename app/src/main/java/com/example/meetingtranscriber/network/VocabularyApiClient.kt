package com.example.meetingtranscriber.network

import android.util.Log
import com.example.meetingtranscriber.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class VocabularyApiClient {

    companion object {
        private const val TAG = "VocabularyApiClient"
        private const val API_HOST = "tingwu.cn-beijing.aliyuncs.com"
        private const val API_BASE = "https://$API_HOST/openapi/tingwu/v2"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun rfc1123Date(): String {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("GMT")
            return sdf.format(Date())
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun createVocabulary(
        name: String,
        words: List<String>,
        language: String = "cn",
        accessKeyId: String = BuildConfig.ALIYUN_ACCESS_KEY_ID,
        accessKeySecret: String = BuildConfig.ALIYUN_ACCESS_KEY_SECRET
    ): String? = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("VocabularyName", name)
                put("Language", language)
                put("WordList", JSONArray(words))
            }

            val path = "/openapi/tingwu/v2/vocabularies"
            val date = rfc1123Date()
            val bodyStr = bodyJson.toString()
            val signature = AliyunSigner.sign(accessKeyId, accessKeySecret, "POST", path, "", bodyStr, date)

            val request = Request.Builder()
                .url("$API_BASE/vocabularies")
                .header("Authorization", signature)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Date", date)
                .header("Host", API_HOST)
                .post(bodyStr.toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) {
                Log.e(TAG, "创建词库失败: HTTP ${response.code}, $responseBody")
                return@withContext null
            }

            val json = JSONObject(responseBody)
            json.optJSONObject("Data")?.optString("VocabularyId")
        } catch (e: Exception) {
            Log.e(TAG, "创建词库异常: ${e.message}")
            null
        }
    }

    suspend fun deleteVocabulary(
        vocabularyId: String,
        accessKeyId: String = BuildConfig.ALIYUN_ACCESS_KEY_ID,
        accessKeySecret: String = BuildConfig.ALIYUN_ACCESS_KEY_SECRET
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val path = "/openapi/tingwu/v2/vocabularies/$vocabularyId"
            val date = rfc1123Date()
            val signature = AliyunSigner.sign(accessKeyId, accessKeySecret, "DELETE", path, "", "", date)

            val request = Request.Builder()
                .url("$API_BASE/vocabularies/$vocabularyId")
                .header("Authorization", signature)
                .header("Accept", "application/json")
                .header("Date", date)
                .header("Host", API_HOST)
                .delete()
                .build()

            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "删除词库异常: ${e.message}")
            false
        }
    }
}
