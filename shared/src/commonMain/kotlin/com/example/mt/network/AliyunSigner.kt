package com.example.mt.network

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云 OpenAPI ROA 签名工具（跨平台，无 Android 依赖）。
 *
 * 用于通义听悟 REST API（CreateTask / StopTask / GetTaskResult）的请求签名。
 * 参考: https://help.aliyun.com/document_detail/315525.html
 */
object AliyunSigner {

    fun sign(
        accessKeyId: String,
        accessKeySecret: String,
        method: String,
        path: String,
        query: String,
        body: String,
        date: String,
    ): String {
        val accept = "application/json"
        val contentType = "application/json; charset=utf-8"

        // 计算 content-MD5（如有 body）
        val contentMD5 = if (body.isNotBlank()) {
            val md5 = MessageDigest.getInstance("MD5")
            java.util.Base64.getEncoder().encodeToString(md5.digest(body.toByteArray(Charsets.UTF_8)))
        } else ""

        val canonicalizedHeaders = ""

        val canonicalizedResource = if (query.isNotBlank()) {
            val sorted = query.split("&").sorted().joinToString("&")
            "$path?$sorted"
        } else path

        val stringToSign = listOf(
            method.uppercase(),
            accept,
            contentMD5,
            contentType,
            date,
            canonicalizedHeaders + canonicalizedResource,
        ).joinToString("\n")

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(accessKeySecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val rawSignature = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
        val signature = java.util.Base64.getEncoder().encodeToString(rawSignature)

        return "acs $accessKeyId:$signature"
    }
}
