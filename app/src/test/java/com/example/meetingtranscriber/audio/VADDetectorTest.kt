package com.example.meetingtranscriber.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VADDetectorTest {

    @Test
    fun `silent buffer returns false`() {
        val vad = VADDetector(onsetThreshold = 200f, offsetThreshold = 100f, silenceFrames = 3)
        val silence = ByteArray(3200) // 3200 bytes of zeros = silence

        repeat(10) {
            assertFalse("silence frame should return false", vad.isVoice(silence))
        }
    }

    @Test
    fun `loud buffer returns true after onset`() {
        val vad = VADDetector(onsetThreshold = 200f, offsetThreshold = 100f, silenceFrames = 3)
        val loud = generateTone(amplitude = 0.5f, frequency = 440, sampleRate = 16000, durationMs = 100)

        // First frame should trigger onset
        assertTrue(vad.isVoice(loud))
    }

    @Test
    fun `hysteresis keeps speech true during brief silence`() {
        val vad = VADDetector(onsetThreshold = 200f, offsetThreshold = 100f, silenceFrames = 5)
        val loud = generateTone(amplitude = 0.5f, frequency = 440, sampleRate = 16000, durationMs = 100)
        val silence = ByteArray(3200)

        // Start speech
        vad.isVoice(loud)
        assertTrue(vad.isVoice(loud))

        // 2 silent frames should not end speech (hysteresis = 5)
        repeat(2) { assertTrue(vad.isVoice(silence)) }
    }

    @Test
    fun `speech ends after enough silent frames`() {
        val vad = VADDetector(onsetThreshold = 200f, offsetThreshold = 100f, silenceFrames = 3)
        val loud = generateTone(amplitude = 0.5f, frequency = 440, sampleRate = 16000, durationMs = 100)
        val silence = ByteArray(3200)

        vad.isVoice(loud)
        assertTrue(vad.isVoice(loud))

        // 3 silent frames should end speech
        repeat(2) { vad.isVoice(silence) }
        assertFalse("speech ends after 3 silence frames", vad.isVoice(silence))
    }

    @Test
    fun `reset clears VAD state`() {
        val vad = VADDetector(onsetThreshold = 200f, offsetThreshold = 100f, silenceFrames = 3)
        val loud = generateTone(amplitude = 0.5f, frequency = 440, sampleRate = 16000, durationMs = 100)
        val silence = ByteArray(3200)

        vad.isVoice(loud)
        assertTrue(vad.isVoice(loud))

        vad.reset()
        assertFalse("after reset, silence should return false", vad.isVoice(silence))
    }

    @Test
    fun `low amplitude audio below threshold returns false`() {
        val vad = VADDetector(onsetThreshold = 1000f, offsetThreshold = 500f, silenceFrames = 3)
        val quiet = generateTone(amplitude = 0.02f, frequency = 440, sampleRate = 16000, durationMs = 100)

        repeat(5) {
            assertFalse(vad.isVoice(quiet))
        }
    }

    @Test
    fun `white noise is detected as voice`() {
        val vad = VADDetector(onsetThreshold = 200f, offsetThreshold = 100f, silenceFrames = 3)
        val noise = ByteArray(3200)
        java.util.Random(42).nextBytes(noise) // random bytes = high energy

        assertTrue(vad.isVoice(noise))
    }

    @Test
    fun `long speech session does not drift`() {
        val vad = VADDetector(onsetThreshold = 200f, offsetThreshold = 100f, silenceFrames = 5)
        val loud = generateTone(amplitude = 0.5f, frequency = 440, sampleRate = 16000, durationMs = 100)

        // Simulate 100 frames of continuous speech
        repeat(100) { assertTrue(vad.isVoice(loud)) }

        // Then silence should eventually turn off
        val silence = ByteArray(3200)
        repeat(5) { vad.isVoice(silence) }
        assertFalse(vad.isVoice(silence))
    }

    @Test
    fun `brief loud burst then silence stays speech during hysteresis`() {
        val vad = VADDetector(onsetThreshold = 200f, offsetThreshold = 100f, silenceFrames = 5)
        val loud = generateTone(amplitude = 0.8f, frequency = 1000, sampleRate = 16000, durationMs = 100)
        val silence = ByteArray(3200)

        // Single loud burst triggers onset
        assertTrue(vad.isVoice(loud))

        // Still speech during hysteresis window
        repeat(4) { assertTrue(vad.isVoice(silence)) }
        // 5th silence frame ends speech
        assertFalse(vad.isVoice(silence))
    }

    @Test
    fun `alternating speech and silence toggles correctly`() {
        val vad = VADDetector(onsetThreshold = 200f, offsetThreshold = 100f, silenceFrames = 3)
        val loud = generateTone(amplitude = 0.5f, frequency = 440, sampleRate = 16000, durationMs = 100)
        val silence = ByteArray(3200)

        // Round 1
        assertTrue(vad.isVoice(loud))
        repeat(3) { vad.isVoice(silence) }
        assertFalse(vad.isVoice(silence))

        // Round 2 — can re-detect speech after silence
        assertTrue(vad.isVoice(loud))
        repeat(3) { vad.isVoice(silence) }
        assertFalse(vad.isVoice(silence))

        // Round 3
        assertTrue(vad.isVoice(loud))
        assertTrue(vad.isVoice(loud))
    }

    @Test
    fun `default constructor creates usable detector`() {
        val vad = VADDetector()
        val silence = ByteArray(3200)
        val loud = generateTone(amplitude = 0.8f, frequency = 440, sampleRate = 16000, durationMs = 100)

        assertFalse(vad.isVoice(silence))
        assertTrue(vad.isVoice(loud))
    }

    @Test
    fun `very short frame below 2 bytes returns false`() {
        val vad = VADDetector(onsetThreshold = 10f, offsetThreshold = 5f, silenceFrames = 3)
        // Single byte — can't form a sample
        assertFalse(vad.isVoice(ByteArray(1)))
    }

    @Test
    fun `medium volume below onset stays silent`() {
        // onset=10000 is above RMS of 0.2 amplitude sine (~4600)
        val vad = VADDetector(onsetThreshold = 10000f, offsetThreshold = 500f, silenceFrames = 3)
        val medium = generateTone(amplitude = 0.2f, frequency = 440, sampleRate = 16000, durationMs = 100)

        // Below onset → no speech
        assertFalse(vad.isVoice(medium))
    }

    // Generate a simple sine wave PCM 16-bit mono buffer
    private fun generateTone(amplitude: Float, frequency: Int, sampleRate: Int, durationMs: Int): ByteArray {
        val numSamples = sampleRate * durationMs / 1000
        val buffer = ByteArray(numSamples * 2) // 16-bit = 2 bytes per sample
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val sample = (amplitude * Short.MAX_VALUE * kotlin.math.sin(2.0 * kotlin.math.PI * frequency * t)).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            buffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            buffer[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        return buffer
    }
}
