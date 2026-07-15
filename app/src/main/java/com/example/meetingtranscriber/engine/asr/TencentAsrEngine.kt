package com.example.meetingtranscriber.engine.asr

import android.util.Base64
import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.engine.AsrConfig
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
 *
 * 协议: wss://asr.cloud.tencent.com/asr/v2/{appid}?{参数}&signature=
 * 签名: Base64(HmacSHA1("asr.cloud.tencent.com/asr/v2/{appid}?" + 字典序参数, SecretKey))
 * 流程: 握手成功即可发二进制音频 → {"type":"end"} 结束
 * 结果: {"code":0,"result":{"slice_type":0|1|2,"voice_text_str":...}}，slice_type 2=最终
 */
class TencentAsrEngine(prefs: PreferencesManager) : CloudAsrWsEngine(prefs) {

    override val tag = "TencentAsrEngine"
    override val provider = CloudAsrProvider.TENCENT

    override fun buildRequest(config: AsrConfig): Request {
        val appId = provider.credential(prefs, 0).trim()
        val secretId = provider.credential(prefs, 1).trim()
        val secretKey = provider.credential(prefs, 2).trim()
        val now = System.currentTimeMillis() / 1000

        // 参数按字典序排列（签名对顺序敏感）
        val params = sortedMapOf(
            "engine_model_type" to "16k_zh",
            "expired" to (now + 3600).toString(),
            "needvad" to "1",
            "nonce" to Random.nextInt(1, 1_000_000_000).toString(),
            "secretid" to secretId,
            "timestamp" to now.toString(),
            "voice_format" to "1",  // pcm
            "voice_id" to UUID.randomUUID().toString()
        )
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val signSrc = "$HOST/asr/v2/$appId?$query"

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = Base64.encodeToString(
            mac.doFinal(signSrc.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

        // signature 须 urlencode（+ = 等字符不编码会偶发鉴权失败）
        val url = "wss://$signSrc&signature=${URLEncoder.encode(signature, "UTF-8")}"
        return Request.Builder().url(url).build()
    }

    override fun onWsOpen(ws: WebSocket, config: AsrConfig) {
        // 签名在 HTTP 升级阶段校验，连接成功即可发音频
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
                endMs = result.optLong("end_time", 0)
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
