package com.example.mt.engine.asr

import com.example.mt.config.EngineKeys
import com.example.mt.engine.AsrConfig
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞实时语音转写（RTASR）。
 */
class XfyunEngine(keys: EngineKeys) : CloudAsrWsEngine(keys) {

    override val tag = "XfyunEngine"
    override val provider = CloudAsrProvider.XFYUN

    override fun buildRequest(config: AsrConfig): Request {
        val appId = provider.credential(keys, 0).trim()
        val apiKey = provider.credential(keys, 1).trim()
        val ts = (System.currentTimeMillis() / 1000).toString()

        val md5Hex = MessageDigest.getInstance("MD5")
            .digest((appId + ts).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signa = java.util.Base64.getEncoder().encodeToString(
            mac.doFinal(md5Hex.toByteArray(Charsets.UTF_8)))

        val url = "$WS_URL?appid=$appId&ts=$ts" +
                "&signa=${URLEncoder.encode(signa, "UTF-8")}" +
                "&lang=${if (config.language == "cn") "cn" else config.language}" +
                "&roleType=2"

        return Request.Builder().url(url).build()
    }

    override fun onWsOpen(ws: WebSocket, config: AsrConfig) {
        // 等服务端 {"action":"started"} 再发音频
    }

    override fun handleTextMessage(text: String) {
        val json = JSONObject(text)
        when (json.optString("action")) {
            "started" -> markReadyForAudio()
            "result" -> parseResult(json.optString("data", ""))
            "error" -> reportError(json.optString("desc",
                "code=${json.optString("code", "?")}").ifBlank { "识别错误" })
        }
    }

    private fun parseResult(data: String) {
        if (data.isBlank()) return
        val st = JSONObject(data).optJSONObject("cn")?.optJSONObject("st") ?: return
        val sb = StringBuilder()
        var speakerId = "0"
        val rt = st.optJSONArray("rt")
        if (rt != null) {
            for (i in 0 until rt.length()) {
                val ws = rt.optJSONObject(i)?.optJSONArray("ws") ?: continue
                for (j in 0 until ws.length()) {
                    val cw = ws.optJSONObject(j)?.optJSONArray("cw") ?: continue
                    val first = cw.optJSONObject(0) ?: continue
                    sb.append(first.optString("w", ""))
                    val rl = first.optString("rl", "")
                    if (rl.isNotBlank() && rl != "0") speakerId = rl
                }
            }
        }
        val content = sb.toString()
        if (content.isBlank()) return
        if (st.optString("type") == "0") {
            emitSentence(
                text = content,
                speakerId = speakerId,
                startMs = st.optString("bg", "0").toLongOrNull() ?: 0,
                endMs = st.optString("ed", "0").toLongOrNull() ?: 0,
            )
        } else {
            setInterim(content)
        }
    }

    override fun sendFinishFrame(ws: WebSocket) {
        ws.send("{\"end\": true}")
    }

    companion object {
        private const val WS_URL = "wss://rtasr.xfyun.cn/v1/ws"
    }
}
