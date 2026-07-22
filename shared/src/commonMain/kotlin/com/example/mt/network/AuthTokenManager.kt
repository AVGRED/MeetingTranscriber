package com.example.mt.network

import io.github.aakira.napier.Napier
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云鉴权 Token 管理器（跨平台，无 Android 依赖）。
 *
 * 使用 RAM AccessKey 生成通义听悟所需的 NLS Bearer Token（JWT 格式，HMAC-SHA256 签名）。
 * Token 有效期 72 小时。
 */
class AuthTokenManager {

    /**
     * 获取通义听悟所需的 Bearer Token。
     * @param accessKeyId 阿里云 AccessKey ID
     * @param accessKeySecret 阿里云 AccessKey Secret
     * @return Bearer Token 字符串，失败返回 null
     */
    fun getToken(accessKeyId: String, accessKeySecret: String): String? {
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            Napier.w("$TAG: 未配置阿里云 AccessKey")
            return null
        }
        return try {
            generateNlsToken(accessKeyId, accessKeySecret)
        } catch (e: Exception) {
            Napier.e("$TAG: 获取 Token 失败: ${e.message}")
            null
        }
    }

    private fun generateNlsToken(accessKeyId: String, accessKeySecret: String): String {
        val expireTime = System.currentTimeMillis() / 1000 + 72 * 3600

        val header = """{"alg":"HS256","typ":"JWT"}"""
        val payload = """{"sub":"$accessKeyId","exp":$expireTime}"""

        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val headerB64 = encoder.encodeToString(header.toByteArray())
        val payloadB64 = encoder.encodeToString(payload.toByteArray())
        val message = "$headerB64.$payloadB64"

        val signature = hmacSha256(accessKeySecret, message)
        val signatureB64 = encoder.encodeToString(signature)

        return "$message.$signatureB64"
    }

    private fun hmacSha256(key: String, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data.toByteArray())
    }

    companion object {
        private const val TAG = "AuthTokenManager"
    }
}
