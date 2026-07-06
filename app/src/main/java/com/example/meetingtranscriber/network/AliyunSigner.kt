package com.example.meetingtranscriber.network

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云 OpenAPI ROA 签名工具
 *
 * 用于通义听悟 REST API（CreateTask / StopTask / GetTaskResult）的请求签名。
 * 参考: https://help.aliyun.com/document_detail/315525.html
 *
 * ROA 签名流程：
 *   StringToSign = VERB + "\n" + Accept + "\n" + Content-MD5 + "\n"
 *                + Content-Type + "\n" + Date + "\n"
 *                + CanonicalizedHeaders + CanonicalizedResource
 *   Signature    = Base64(HMAC-SHA1(AccessKeySecret, StringToSign))
 *   Authorization = "acs " + AccessKeyId + ":" + Signature
 */
object AliyunSigner {

    fun sign(
        accessKeyId: String,
        accessKeySecret: String,
        method: String,
        path: String,
        query: String,
        body: String,
        date: String
    ): String {
        // 必须与实际请求头完全一致（含 charset）
        val accept = "application/json"
        val contentType = "application/json; charset=utf-8"

        // 1. Content-MD5 — 留空（请求不携带此头，服务器不计入签名）
        val contentMD5 = ""

        // 2. CanonicalizedHeaders (仅 x-acs- 前缀头部，按小写字母序)
        //    当前请求不使用 x-acs- 自定义头
        val canonicalizedHeaders = ""

        // 3. CanonicalizedResource (path + sorted query)
        val canonicalizedResource = if (query.isNotBlank()) {
            val sorted = query.split("&").sorted().joinToString("&")
            "$path?$sorted"
        } else path

        // 4. StringToSign
        val stringToSign = listOf(
            method.uppercase(),
            accept,
            contentMD5,
            contentType,
            date,
            canonicalizedHeaders + canonicalizedResource
        ).joinToString("\n")

        // 5. HMAC-SHA1 签名
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(accessKeySecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val rawSignature = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
        val signature = Base64.encodeToString(rawSignature, Base64.NO_WRAP)

        // 6. Authorization 头
        return "acs $accessKeyId:$signature"
    }
}
