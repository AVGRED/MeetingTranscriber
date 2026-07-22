package com.example.mt.security

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Windows Desktop 密钥存储（MVP：文件主密钥 + AES-256-GCM）。
 *
 * Phase 2 升级方向：主密钥文件用 Windows DPAPI (CryptProtectData) 保护，
 * 使只有当前 Windows 用户才能解密。
 *
 * 密文格式：[12 bytes IV][ciphertext + 16 bytes GCM tag] → Base64
 */
class DesktopKeyStore(
    private val masterKeyFile: File = File(
        System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir") ?: ".",
        "MeetingTranscriber/data/.masterkey"
    ),
) {
    private val masterKey: SecretKey by lazy { loadOrCreateMasterKey() }
    private val random = SecureRandom()

    private val GCM_TAG_LENGTH = 128   // bits
    private val GCM_IV_LENGTH = 12     // bytes

    /** 是否已成功加载/创建主密钥 */
    val isAvailable: Boolean by lazy {
        runCatching { masterKey }.isSuccess
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH).also { random.nextBytes(it) }
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, spec)

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // iv + ciphertext (includes GCM tag at end in Java)
        val combined = iv + ciphertext
        return combined.encodeBase64()
    }

    fun decrypt(encodedCiphertext: String): String? {
        return try {
            val combined = encodedCiphertext.decodeBase64()
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)

            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            // 解码失败（数据损坏/篡改）vs 密钥损坏——不同模因
            System.err.println("DesktopKeyStore: decrypt failed (data corrupt/tampered): ${e.message}")
            null
        } catch (e: Exception) {
            System.err.println("DesktopKeyStore: decrypt failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun loadOrCreateMasterKey(): SecretKey {
        masterKeyFile.parentFile?.mkdirs()

        return if (masterKeyFile.exists()) {
            val encoded = masterKeyFile.readText().decodeBase64()
            SecretKeySpec(encoded, "AES")
        } else {
            val keyBytes = ByteArray(32) // AES-256
            random.nextBytes(keyBytes)
            masterKeyFile.writeText(keyBytes.encodeBase64())
            // 设置文件为仅 owner 可读写 (Windows 上基础保护)
            try {
                masterKeyFile.setReadable(false, false)
                masterKeyFile.setReadable(true, true)
                masterKeyFile.setWritable(false, false)
                masterKeyFile.setWritable(true, true)
            } catch (_: Exception) { /* best-effort */ }
            SecretKeySpec(keyBytes, "AES")
        }
    }
}

private fun ByteArray.encodeBase64(): String =
    java.util.Base64.getEncoder().encodeToString(this)

private fun String.decodeBase64(): ByteArray =
    java.util.Base64.getDecoder().decode(this)
