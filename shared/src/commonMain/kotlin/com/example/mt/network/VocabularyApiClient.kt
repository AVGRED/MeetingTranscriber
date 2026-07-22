package com.example.mt.network

import io.github.aakira.napier.Napier
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

/**
 * 通义听悟热词/词汇表 API 客户端（跨平台，无 Android 依赖）。
 */
class VocabularyApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun createVocabulary(
        name: String,
        words: List<String>,
        language: String = "cn",
        accessKeyId: String,
        accessKeySecret: String,
    ): String? = withContext(Dispatchers.Default) {
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
                Napier.e("$TAG: 创建词库失败: HTTP ${response.code}, $responseBody")
                return@withContext null
            }

            JSONObject(responseBody).optJSONObject("Data")?.optString("VocabularyId")
        } catch (e: Exception) {
            Napier.e("$TAG: 创建词库异常: ${e.message}")
            null
        }
    }

    suspend fun deleteVocabulary(
        vocabularyId: String,
        accessKeyId: String,
        accessKeySecret: String,
    ): Boolean = withContext(Dispatchers.Default) {
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
            Napier.e("$TAG: 删除词库异常: ${e.message}")
            false
        }
    }

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
}
