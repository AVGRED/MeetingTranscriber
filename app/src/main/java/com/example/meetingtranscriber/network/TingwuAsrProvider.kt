package com.example.meetingtranscriber.network

import android.util.Log

/**
 * 通义听悟（Aliyun Tingwu）ASR Provider 适配器。
 *
 * 将现有的 [TingwuApiClient] + [AsrWebSocketClient] 流程封装为 [AsrProvider] 接口，
 * 保持当前行为不变，同时使 [com.example.meetingtranscriber.ui.meeting.MeetingViewModel]
 * 不直接依赖具体实现。
 */
class TingwuAsrProvider : AsrProvider {

    companion object {
        private const val TAG = "TingwuAsrProvider"
    }

    // ── 内部组件 ──

    private val asrClient = AsrWebSocketClient()
    private val tingwuApi = TingwuApiClient()

    /** 当前转写任务 ID（用于 stopTask） */
    private var currentTaskId: String = ""

    // ── AsrProvider 回调 ──

    override var onInterimResult: ((String) -> Unit)? = null
        set(value) { field = value; asrClient.onInterimResult = value }

    override var onSentenceResult: ((AsrSentenceResult) -> Unit)? = null
        set(value) { field = value; asrClient.onSentenceResult = value }

    override var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
        set(value) { field = value; asrClient.onConnectionStateChanged = value }

    override var onError: ((String) -> Unit)? = null
        set(value) { field = value; asrClient.onError = value }

    // ── 操作 ──

    override suspend fun start(config: AsrConfig): Boolean {
        val taskResult = tingwuApi.createRealtimeTask(
            sourceLanguage = config.language,
            vocabularyId = config.vocabularyId
        )
        if (taskResult == null || !taskResult.isValid) {
            Log.e(TAG, "创建通义听悟任务失败")
            return false
        }
        currentTaskId = taskResult.taskId
        asrClient.connect(taskResult.meetingJoinUrl, taskResult.taskId)
        return true
    }

    override fun sendAudio(pcmData: ByteArray) {
        asrClient.sendAudio(pcmData)
    }

    override fun disconnect() {
        asrClient.disconnect()
    }

    override suspend fun stopTask() {
        if (currentTaskId.isBlank()) return
        try {
            tingwuApi.stopTask(currentTaskId)
        } catch (_: Exception) {
            Log.w(TAG, "停止任务失败")
        }
    }

    // ── 状态查询 ──

    override fun getCurrentTaskId(): String = currentTaskId

    override fun getOverflowFilePath(): String? = asrClient.getOverflowFilePath()

    override fun getBufferSize(): Int = asrClient.getBufferSize()
}
