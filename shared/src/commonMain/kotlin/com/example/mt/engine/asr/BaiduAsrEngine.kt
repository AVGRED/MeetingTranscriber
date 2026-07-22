package com.example.mt.engine.asr

import com.example.mt.config.EngineKeys
import com.example.mt.engine.AsrConfig
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONObject
import java.util.UUID

/**
 * 百度智能云实时语音识别。
 */
class BaiduAsrEngine(keys: EngineKeys) : CloudAsrWsEngine(keys) {

    override val tag = "BaiduAsrEngine"
    override val provider = CloudAsrProvider.BAIDU

    override fun buildRequest(config: AsrConfig): Request =
        Request.Builder().url("$WS_URL?sn=${UUID.randomUUID()}").build()

    override fun onWsOpen(ws: WebSocket, config: AsrConfig) {
        val appIdStr = provider.credential(keys, 0).trim()
        val start = JSONObject().apply {
            put("type", "START")
            put("data", JSONObject().apply {
                appIdStr.toLongOrNull()?.let { put("appid", it) } ?: put("appid", appIdStr)
                put("appkey", provider.credential(keys, 1).trim())
                put("dev_pid", DEV_PID)
                put("cuid", "meeting-transcriber")
                put("format", "pcm")
                put("sample", 16000)
            })
        }
        ws.send(start.toString())
        markReadyForAudio()
    }

    override fun handleTextMessage(text: String) {
        val json = JSONObject(text)
        val errNo = json.optInt("err_no", 0)
        if (errNo != 0) {
            reportError("err_no=$errNo: ${json.optString("err_msg", "识别错误")}")
            return
        }
        val content = json.optString("result", "")
        if (content.isBlank()) return
        when (json.optString("type")) {
            "FIN_TEXT" -> emitSentence(
                text = content,
                startMs = json.optLong("start_time", 0),
                endMs = json.optLong("end_time", 0),
            )
            "MID_TEXT" -> setInterim(content)
        }
    }

    override fun sendFinishFrame(ws: WebSocket) {
        ws.send("{\"type\": \"FINISH\"}")
    }

    companion object {
        private const val WS_URL = "wss://vop.baidu.com/realtime_asr"
        private const val DEV_PID = 15372
    }
}
