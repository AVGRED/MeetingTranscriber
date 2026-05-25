package com.example.meetingtranscriber.network

import android.util.Base64
import android.util.Log
import com.example.meetingtranscriber.BuildConfig
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云鉴权 Token 管理器
 *
 * 使用 RAM AccessKey 生成通义听悟所需的 NLS Bearer Token（JWT 格式，HMAC-SHA256 签名）。
 * Token 有效期 72 小时。
 */
class AuthTokenManager {

    companion object {
        private const val TAG = "AuthTokenManager"
    }

    /**
     * 获取通义听悟所需的 Bearer Token
     * @return Bearer Token 字符串，失败返回 null
     */
    fun getToken(): String? {
        val accessKeyId = BuildConfig.ALIYUN_ACCESS_KEY_ID
        val accessKeySecret = BuildConfig.ALIYUN_ACCESS_KEY_SECRET

        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            Log.w(TAG, "未配置阿里云 AccessKey")
            return null
        }

        return try {
            generateNlsToken(accessKeyId, accessKeySecret)
        } catch (e: Exception) {
            Log.e(TAG, "获取 Token 失败: ${e.message}")
            null
        }
    }

    private fun generateNlsToken(accessKeyId: String, accessKeySecret: String): String {
        val expireTime = System.currentTimeMillis() / 1000 + 72 * 3600

        val header = """{"alg":"HS256","typ":"JWT"}"""
        val payload = """{"sub":"$accessKeyId","exp":$expireTime}"""

        val headerB64 = Base64.encodeToString(header.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        val payloadB64 = Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        val message = "$headerB64.$payloadB64"

        val signature = hmacSha256(accessKeySecret, message)
        val signatureB64 = Base64.encodeToString(signature, Base64.NO_WRAP or Base64.URL_SAFE)

        return "$message.$signatureB64"
    }

    private fun hmacSha256(key: String, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data.toByteArray())
    }
}
