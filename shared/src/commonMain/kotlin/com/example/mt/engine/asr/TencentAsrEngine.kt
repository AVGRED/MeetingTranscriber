package com.example.mt.engine.asr

import com.example.mt.config.EngineKeys
import com.example.mt.engine.AsrConfig
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 腾讯云实时语音识别。
 */
class TencentAsrEngine(keys: EngineKeys) : CloudAsrWsEngine(keys) {

    override val tag = "TencentAsrEngine"
    override val provider = CloudAsrProvider.TENCENT

    override fun buildRequest(config: AsrConfig): Request {
        val appId = provider.credential(keys, 0).trim()
        val secretId = provider.credential(keys, 1).trim()
        val secretKey = provider.credential(keys, 2).trim()
        val now = System.currentTimeMillis() / 1000

        val params = sortedMapOf(
            "engine_model_type" to "16k_zh",
            "expired" to (now + 3600).toString(),
            "needvad" to "1",
            "nonce" to Random.nextInt(1, 1_000_000_000).toString(),
            "secretid" to secretId,
            "timestamp" to now.toString(),
            "voice_format" to "1",
            "voice_id" to UUID.randomUUID().toString(),
        )
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val signSrc = "$HOST/asr/v2/$appId?$query"

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = java.util.Base64.getEncoder().encodeToString(
            mac.doFinal(signSrc.toByteArray(Charsets.UTF_8)))

        val url = "wss://$signSrc&signature=${URLEncoder.encode(signature, "UTF-8")}"
        return Request.Builder().url(url).build()
    }

    override fun onWsOpen(ws: WebSocket, config: AsrConfig) {
        markReadyForAudio()
    }

    override fun handleTextMessage(text: String) {
        val json = JSONObject(text)
        val code = json.optInt("code", 0)
        if (code != 0) {
            reportError("code=$code: ${json.optString("message", "识别错误")}")
            return
        }
        val result = json.optJSONObject("result") ?: return
        val content = result.optString("voice_text_str", "")
        if (content.isBlank()) return
        if (result.optInt("slice_type", 1) == 2) {
            emitSentence(
                text = content,
                startMs = result.optLong("start_time", 0),
                endMs = result.optLong("end_time", 0),
            )
        } else {
            setInterim(content)
        }
    }

    override fun sendFinishFrame(ws: WebSocket) {
        ws.send("{\"type\": \"end\"}")
    }

    companion object {
        private const val HOST = "asr.cloud.tencent.com"
    }
}
