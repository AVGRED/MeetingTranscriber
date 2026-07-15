package com.example.meetingtranscriber.engine.asr

import com.example.meetingtranscriber.PreferencesManager
import com.example.meetingtranscriber.engine.AsrConfig
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 阿里 Paraformer 实时语音识别（DashScope WebSocket）。
 *
 * 协议: wss://dashscope.aliyuncs.com/api-ws/v1/inference
 * 鉴权: Bearer API Key（与通义千问 LLM 共用 DashScope Key）
 * 流程: run-task → task-started → 二进制音频 → finish-task → task-finished
 * 结果: result-generated 事件，payload.output.sentence（sentence_end 区分中间/最终）
 */
class ParaformerEngine(prefs: PreferencesManager) : CloudAsrWsEngine(prefs) {

    override val tag = "ParaformerEngine"
    override val provider = CloudAsrProvider.PARAFORMER

    private var taskId: String = ""

    override fun buildRequest(config: AsrConfig): Request {
        val apiKey = provider.credential(prefs, 0)
        return Request.Builder()
            .url(WS_URL)
            .header("Authorization", "Bearer $apiKey")
            .build()
    }

    override fun onWsOpen(ws: WebSocket, config: AsrConfig) {
        taskId = UUID.randomUUID().toString()
        val runTask = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "run-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("task_group", "audio")
                put("task", "asr")
                put("function", "recognition")
                put("model", MODEL)
                put("parameters", JSONObject().apply {
                    put("format", "pcm")
                    put("sample_rate", 16000)
                    // cn → zh；方言/多语种自动检测由 v2 模型内置
                    if (config.language == "cn") put("language_hints", JSONArray().put("zh"))
                })
                put("input", JSONObject())
            })
        }
        ws.send(runTask.toString())
        // 音频须等 task-started 事件后再发（handleTextMessage 里 markReadyForAudio）
    }

    override fun handleTextMessage(text: String) {
        val json = JSONObject(text)
        val header = json.optJSONObject("header") ?: return
        when (header.optString("event")) {
            "task-started" -> markReadyForAudio()
            "result-generated" -> {
                val sentence = json.optJSONObject("payload")
                    ?.optJSONObject("output")
                    ?.optJSONObject("sentence") ?: return
                val content = sentence.optString("text", "")
                if (content.isBlank()) return
                if (sentence.optBoolean("sentence_end", false)) {
                    emitSentence(
                        text = content,
                        startMs = sentence.optLong("begin_time", 0),
                        endMs = sentence.optLong("end_time", 0)
                    )
                } else {
                    setInterim(content)
                }
            }
            "task-failed" -> reportError(
                header.optString("error_message", "任务失败").ifBlank { "任务失败" })
            // task-finished：正常收尾，无需处理
        }
    }

    override fun sendFinishFrame(ws: WebSocket) {
        val finish = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "finish-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply { put("input", JSONObject()) })
        }
        ws.send(finish.toString())
    }

    companion object {
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        private const val MODEL = "paraformer-realtime-v2"
    }
}
