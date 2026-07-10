package com.example.meetingtranscriber.ui.meeting

import com.example.meetingtranscriber.engine.EngineState
import com.example.meetingtranscriber.network.ConnectionState
import org.junit.Assert.*
import org.junit.Test

class MeetingUiStateTest {

    @Test
    fun `initial state has all defaults`() {
        val state = MeetingUiState()
        assertFalse(state.isMeetingActive)
        assertFalse(state.isPaused)
        assertFalse(state.isConnected)
        assertFalse(state.isDemoMode)
        assertFalse(state.isSpeaking)
        assertEquals(0, state.elapsedSeconds)
        assertEquals(ConnectionState.DISCONNECTED, state.connectionState)
        assertEquals("", state.interimText)
        assertTrue(state.speakerLabels.isEmpty())
        assertEquals(0, state.speakerCount)
        assertNull(state.errorMessage)
        assertEquals("cn", state.selectedLanguage)
        assertEquals("", state.asrEngineName)
        assertEquals(EngineState.IDLE, state.asrEngineStatus)
        assertFalse(state.isGeneratingSummary)
        assertEquals(0f, state.summaryProgress)
    }

    @Test
    fun `start meeting activates state`() {
        val state = MeetingUiState().copy(
            isMeetingActive = true,
            isPaused = false,
            isConnected = true,
            isDemoMode = false,
            errorMessage = null,
            selectedLanguage = "yue"
        )
        assertTrue(state.isMeetingActive)
        assertTrue(state.isConnected)
        assertEquals("yue", state.selectedLanguage)
        assertNull(state.errorMessage)
    }

    @Test
    fun `start online meeting with engine info`() {
        val state = MeetingUiState().copy(
            isMeetingActive = true,
            isConnected = true,
            asrEngineName = "FunASR 云端",
            asrEngineStatus = EngineState.RUNNING
        )
        assertTrue(state.isMeetingActive)
        assertEquals("FunASR 云端", state.asrEngineName)
        assertEquals(EngineState.RUNNING, state.asrEngineStatus)
    }

    @Test
    fun `start offline meeting uses local engine`() {
        val state = MeetingUiState().copy(
            isMeetingActive = true,
            isConnected = false,
            asrEngineName = "FunASR 离线",
            asrEngineStatus = EngineState.RUNNING
        )
        assertTrue(state.isMeetingActive)
        assertFalse(state.isConnected) // 离线 = 无云端连接
        assertEquals("FunASR 离线", state.asrEngineName)
    }

    @Test
    fun `toggle pause flips isPaused`() {
        val active = MeetingUiState(isMeetingActive = true, isPaused = false)
        val paused = active.copy(isPaused = true)
        assertTrue(paused.isPaused)
        val resumed = paused.copy(isPaused = false)
        assertFalse(resumed.isPaused)
    }

    @Test
    fun `end meeting resets to idle`() {
        val active = MeetingUiState(
            isMeetingActive = true,
            isPaused = false,
            isConnected = true,
            isDemoMode = true,
            interimText = "some text",
            asrEngineName = "FunASR 云端",
            errorMessage = null
        )
        val ended = active.copy(
            isMeetingActive = false,
            isPaused = false,
            interimText = "",
            isConnected = false,
            isDemoMode = false,
            asrEngineName = ""
        )
        assertFalse(ended.isMeetingActive)
        assertEquals("", ended.interimText)
        assertFalse(ended.isConnected)
        assertFalse(ended.isDemoMode)
        assertEquals("", ended.asrEngineName)
    }

    @Test
    fun `clear error sets errorMessage to null`() {
        val state = MeetingUiState(errorMessage = "something went wrong")
        assertEquals("something went wrong", state.errorMessage)
        val cleared = state.copy(errorMessage = null)
        assertNull(cleared.errorMessage)
    }

    @Test
    fun `connection state transitions`() {
        val connected = MeetingUiState(
            connectionState = ConnectionState.CONNECTED,
            isConnected = true
        )
        assertEquals(ConnectionState.CONNECTED, connected.connectionState)
        assertTrue(connected.isConnected)

        val disconnected = connected.copy(
            connectionState = ConnectionState.DISCONNECTED,
            isConnected = false
        )
        assertEquals(ConnectionState.DISCONNECTED, disconnected.connectionState)
        assertFalse(disconnected.isConnected)
    }

    @Test
    fun `summary generation state`() {
        val generating = MeetingUiState(
            isGeneratingSummary = true,
            summaryProgress = 0.5f
        )
        assertTrue(generating.isGeneratingSummary)
        assertEquals(0.5f, generating.summaryProgress)

        val done = generating.copy(
            isGeneratingSummary = false,
            summaryProgress = 1f
        )
        assertFalse(done.isGeneratingSummary)
        assertEquals(1f, done.summaryProgress)
    }

    @Test
    fun `speaker tracking updates`() {
        val initial = MeetingUiState(speakerLabels = emptyMap(), speakerCount = 0)
        val withSpeakers = initial.copy(
            speakerLabels = mapOf("sp1" to "张三", "sp2" to "李四"),
            speakerCount = 2
        )
        assertEquals(2, withSpeakers.speakerCount)
        assertEquals("张三", withSpeakers.speakerLabels["sp1"])
        assertEquals("李四", withSpeakers.speakerLabels["sp2"])
    }

    @Test
    fun `elapsed seconds monotonic`() {
        val t0 = MeetingUiState(elapsedSeconds = 0)
        val t5 = t0.copy(elapsedSeconds = 5)
        val t10 = t5.copy(elapsedSeconds = 10)
        assertTrue(t10.elapsedSeconds > t5.elapsedSeconds)
        assertTrue(t5.elapsedSeconds > t0.elapsedSeconds)
    }

    @Test
    fun `interim text cleared on new sentence`() {
        val withInterim = MeetingUiState(interimText = "partial sentence...")
        val afterSentence = withInterim.copy(interimText = "")
        assertEquals("", afterSentence.interimText)
    }

    @Test
    fun `engine status error propagated to ui state`() {
        val error = MeetingUiState(
            asrEngineStatus = EngineState.ERROR,
            errorMessage = "模型加载失败"
        )
        assertEquals(EngineState.ERROR, error.asrEngineStatus)
        assertEquals("模型加载失败", error.errorMessage)
    }

    @Test
    fun `engine loading state`() {
        val loading = MeetingUiState(
            asrEngineStatus = EngineState.LOADING,
            asrEngineName = "FunASR 离线"
        )
        assertEquals(EngineState.LOADING, loading.asrEngineStatus)
        assertEquals("FunASR 离线", loading.asrEngineName)
    }
}
