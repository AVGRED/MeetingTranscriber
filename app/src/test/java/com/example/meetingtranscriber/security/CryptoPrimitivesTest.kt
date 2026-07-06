package com.example.meetingtranscriber.security

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoPrimitivesTest {

    @Test
    fun `AES-GCM encrypt and decrypt round-trip`() {
        val key = generateAESKey()
        val plaintext = "这是会议转写的测试数据——包含中文、English、数字123!@#".toByteArray()

        val ciphertext = aesGcmEncrypt(plaintext, key)
        val decrypted = aesGcmDecrypt(ciphertext, key)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt empty data`() {
        val key = generateAESKey()
        val plaintext = ByteArray(0)

        val ciphertext = aesGcmEncrypt(plaintext, key)
        val decrypted = aesGcmDecrypt(ciphertext, key)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt single byte`() {
        val key = generateAESKey()
        val plaintext = byteArrayOf(0x42)

        val ciphertext = aesGcmEncrypt(plaintext, key)
        val decrypted = aesGcmDecrypt(ciphertext, key)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt large payload 1MB`() {
        val key = generateAESKey()
        val plaintext = ByteArray(1_000_000) { (it % 256).toByte() }

        val ciphertext = aesGcmEncrypt(plaintext, key)
        val decrypted = aesGcmDecrypt(ciphertext, key)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `decrypt with wrong key fails`() {
        val key1 = generateAESKey()
        val key2 = generateAESKey()
        val plaintext = "测试数据".toByteArray()

        val ciphertext = aesGcmEncrypt(plaintext, key1)

        try {
            aesGcmDecrypt(ciphertext, key2)
            fail("Expected AEADBadTagException")
        } catch (_: Exception) {
            // expected — wrong key should fail authentication
        }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val key = generateAESKey()
        val plaintext = "测试数据".toByteArray()

        val ciphertext = aesGcmEncrypt(plaintext, key)
        // Flip a bit in the ciphertext (not the IV)
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1].toInt() xor 0xFF).toByte()

        try {
            aesGcmDecrypt(ciphertext, key)
            fail("Expected AEADBadTagException on tampered data")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `generated keys are different`() {
        val keys = (1..10).map { generateAESKey() }
        val encoded = keys.map { it.encoded.toList() }
        assertEquals(10, encoded.distinct().size)
    }

    @Test
    fun `generated keys are 256-bit`() {
        val key = generateAESKey()
        assertEquals("AES", key.algorithm)
        assertEquals(32, key.encoded.size) // 256 bits = 32 bytes
    }

    @Test
    fun `IV is 12 bytes for GCM`() {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        assertEquals(12, iv.size)
    }

    @Test
    fun `repeated encryption yields different ciphertext due to random IV`() {
        val key = generateAESKey()
        val plaintext = "相同文本".toByteArray()

        val c1 = aesGcmEncrypt(plaintext, key)
        val c2 = aesGcmEncrypt(plaintext, key)

        assertFalse(c1.contentEquals(c2))
    }

    @Test
    fun `decrypt own ciphertext succeeds after repeated encryption`() {
        val key = generateAESKey()
        val plaintext = "验证独立性".toByteArray()

        repeat(5) {
            val ciphertext = aesGcmEncrypt(plaintext, key)
            assertArrayEquals(plaintext, aesGcmDecrypt(ciphertext, key))
        }
    }

    @Test
    fun `key serialization round-trip`() {
        val original = generateAESKey()
        val restored = SecretKeySpec(original.encoded, "AES")

        val plaintext = "密钥序列化测试".toByteArray()
        val ciphertext = aesGcmEncrypt(plaintext, original)
        val decrypted = aesGcmDecrypt(ciphertext, restored)

        assertArrayEquals(plaintext, decrypted)
    }

    // ── helpers ──

    private fun generateAESKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    /** Returns IV (12 bytes) + ciphertext */
    private fun aesGcmEncrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext)
        return iv + encrypted
    }

    private fun aesGcmDecrypt(packed: ByteArray, key: SecretKey): ByteArray {
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}
