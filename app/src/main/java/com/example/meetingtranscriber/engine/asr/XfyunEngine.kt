package com.example.meetingtranscriber.engine.asr

import android.util.Base64
import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.engine.AsrConfig
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞实时语音转写（RTASR）。
 *
 * 协议: wss://rtasr.xfyun.cn/v1/ws?appid=&ts=&signa=
 * 签名: signa = Base64(HmacSHA1(MD5hex(appid + ts), apiKey))
 * 流程: 连接 → 服务端 {"action":"started"} → 二进制音频 → {"end": true}
 * 结果: {"action":"result","data":"<json>"}，data.cn.st.type "0"=最终 "1"=中间；
 *       roleType=2 开启角色分离，词级 rl 字段为说话人编号
 */
class XfyunEngine(prefs: PreferencesManager) : CloudAsrWsEngine(prefs) {

    override val tag = "XfyunEngine"
    override val provider = CloudAsrProvider.XFYUN

    override fun buildRequest(config: AsrConfig): Request {
        val appId = provider.credential(prefs, 0).trim()
        val apiKey = provider.credential(prefs, 1).trim()
        val ts = (System.currentTimeMillis() / 1000).toString()

        val md5Hex = MessageDigest.getInstance("MD5")
            .digest((appId + ts).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signa = Base64.encodeToString(
            mac.doFinal(md5Hex.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

        val url = "$WS_URL?appid=$appId&ts=$ts" +
                "&signa=${URLEncoder.encode(signa, "UTF-8")}" +
                "&lang=${if (config.language == "cn") "cn" else config.language}" +
                "&roleType=2"  // 角色分离：会议多说话人场景

        return Request.Builder().url(url).build()
    }

    override fun onWsOpen(ws: WebSocket, config: AsrConfig) {
        // 无握手帧；等服务端 {"action":"started"} 再发音频
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

    /** data 结构: {"cn":{"st":{"type":"0|1","bg":"起始ms","ed":"结束ms",
     *  "rt":[{"ws":[{"cw":[{"w":"词","rl":"角色号"}]}]}]}},"seg_id":N} */
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
                endMs = st.optString("ed", "0").toLongOrNull() ?: 0
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
