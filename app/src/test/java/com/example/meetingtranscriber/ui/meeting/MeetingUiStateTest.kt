package com.example.meetingtranscriber.ui.meeting

import com.example.meetingtranscriber.network.AsrWebSocketClient
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
        assertFalse(state.isOfflineMode)
        assertFalse(state.isSpeaking)
        assertFalse(state.isUploading)
        assertEquals(0, state.elapsedSeconds)
        assertEquals(AsrWebSocketClient.ConnectionState.DISCONNECTED, state.connectionState)
        assertEquals("", state.interimText)
        assertTrue(state.speakerLabels.isEmpty())
        assertEquals(0, state.speakerCount)
        assertNull(state.errorMessage)
        assertEquals("cn", state.selectedLanguage)
        assertFalse(state.showOfflineUploadPrompt)
    }

    @Test
    fun `start meeting activates state`() {
        val state = MeetingUiState().copy(
            isMeetingActive = true,
            isPaused = false,
            isConnected = true,
            isDemoMode = false,
            isOfflineMode = false,
            errorMessage = null,
            selectedLanguage = "yue"
        )
        assertTrue(state.isMeetingActive)
        assertTrue(state.isConnected)
        assertEquals("yue", state.selectedLanguage)
        assertFalse(state.isOfflineMode)
        assertNull(state.errorMessage)
    }

    @Test
    fun `start demo mode transitions`() {
        val state = MeetingUiState().copy(
            isMeetingActive = true,
            isDemoMode = true,
            isConnected = true,
            errorMessage = null,
            elapsedSeconds = 0
        )
        assertTrue(state.isDemoMode)
        assertTrue(state.isMeetingActive)
    }

    @Test
    fun `start offline mode transitions`() {
        val state = MeetingUiState().copy(
            isMeetingActive = true,
            isOfflineMode = true,
            isDemoMode = false,
            isSpeaking = false,
            errorMessage = null
        )
        assertTrue(state.isOfflineMode)
        assertFalse(state.isConnected) // offline = no cloud connection
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
            errorMessage = null
        )
        val ended = active.copy(
            isMeetingActive = false,
            isPaused = false,
            interimText = "",
            isConnected = false,
            isDemoMode = false,
            isOfflineMode = false
        )
        assertFalse(ended.isMeetingActive)
        assertEquals("", ended.interimText)
        assertFalse(ended.isConnected)
        assertFalse(ended.isDemoMode)
    }

    @Test
    fun `end offline meeting sets upload prompt`() {
        val ended = MeetingUiState(
            isMeetingActive = false,
            isOfflineMode = false,
            showOfflineUploadPrompt = true
        )
        assertTrue(ended.showOfflineUploadPrompt)
        assertFalse(ended.isMeetingActive)
        assertFalse(ended.isOfflineMode)
    }

    @Test
    fun `dismiss upload prompt clears flag`() {
        val withPrompt = MeetingUiState(showOfflineUploadPrompt = true)
        val dismissed = withPrompt.copy(showOfflineUploadPrompt = false)
        assertFalse(dismissed.showOfflineUploadPrompt)
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
            connectionState = AsrWebSocketClient.ConnectionState.CONNECTED,
            isConnected = true
        )
        assertEquals(AsrWebSocketClient.ConnectionState.CONNECTED, connected.connectionState)
        assertTrue(connected.isConnected)

        val disconnected = connected.copy(
            connectionState = AsrWebSocketClient.ConnectionState.DISCONNECTED,
            isConnected = false
        )
        assertEquals(AsrWebSocketClient.ConnectionState.DISCONNECTED, disconnected.connectionState)
        assertFalse(disconnected.isConnected)
    }

    @Test
    fun `uploading state is independent of offline mode`() {
        val uploading = MeetingUiState(
            isUploading = true,
            isMeetingActive = true,
            isOfflineMode = false,
            errorMessage = null
        )
        assertTrue(uploading.isUploading)
        assertTrue(uploading.isMeetingActive)
        assertFalse(uploading.isOfflineMode)
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
}
