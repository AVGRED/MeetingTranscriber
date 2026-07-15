package com.example.meetingtranscriber.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.meetingtranscriber.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 密钥管理器 — 所有加密操作的入口
 *
 * 架构：
 *   AndroidKeyStore → Master Key (KEK, AES-256)
 *                        ↓ 加密
 *                  DEK (AES-256) → EncryptedSharedPreferences
 *                        ↓
 *          ┌─────────────┴─────────────┐
 *   getDatabasePassphrase()    getFileSecretKey()
 *   → SQLCipher                → EncryptedFile
 *
 * Debug 模式可关闭加密以方便开发。
 */
object CryptoManager {

    private const val TAG = "CryptoManager"
    private const val KEYSTORE_MASTER_ALIAS = "meeting_transcriber_master_key"
    private const val SECURE_PREFS_NAME = "mt_secure_prefs"
    private const val DEK_KEY = "encrypted_dek"
    private const val DEK_IV_KEY = "encrypted_dek_iv"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private val initLock = Any()
    private val initLatch = java.util.concurrent.CountDownLatch(1)
    @Volatile private var initialized = false
    @Volatile private var enabled = true
    @Volatile private var dek: SecretKey? = null

    fun init(context: Context) {
        synchronized(initLock) {
            if (initialized) return
            enabled = !BuildConfig.DEBUG // 可在开发时强制关闭
            if (!enabled) {
                Log.i(TAG, "Debug 模式，加密已关闭")
                initialized = true
                initLatch.countDown()
                return
            }
            try {
                ensureMasterKey()
                dek = loadOrGenerateDEK(context)
                initialized = true
                Log.i(TAG, "加密初始化完成")
            } catch (e: Exception) {
                Log.e(TAG, "加密初始化失败，降级为明文模式: ${e.message}", e)
                enabled = false
                initialized = true
            } finally {
                initLatch.countDown()
            }
        }
    }

    /**
     * 就绪门：init 已移到后台线程，所有密钥消费方先在此等待，
     * 防止 init 未完成时 dek==null 被误判为"明文模式"。
     */
    private fun awaitInitialized() {
        if (initialized) return
        initLatch.await()
    }

    fun isEncryptionEnabled(): Boolean {
        awaitInitialized()
        return enabled
    }

    /** SQLCipher 密码（hex 编码的 DEK） */
    fun getDatabasePassphrase(): ByteArray {
        awaitInitialized()
        if (!enabled || dek == null) return ByteArray(0)
        return dek!!.encoded.joinToString("") { "%02x".format(it) }.toByteArray()
    }

    /** 文件加密密钥（直接使用 DEK） */
    fun getFileSecretKey(): SecretKey? {
        awaitInitialized()
        if (!enabled || dek == null) return null
        return dek
    }

    // ── 内部实现 ──

    private fun ensureMasterKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        if (ks.containsAlias(KEYSTORE_MASTER_ALIAS)) return

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_MASTER_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        keyGen.generateKey()
    }

    private fun loadOrGenerateDEK(context: Context): SecretKey {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val storedDek = prefs.getString(DEK_KEY, null)
        val storedIv = prefs.getString(DEK_IV_KEY, null)

        if (storedDek != null && storedIv != null) {
            return decryptDEK(storedDek, storedIv)
        }

        // 首次运行：生成 DEK 并安全存储
        val newDek = generateDEK()
        val (encrypted, iv) = encryptDEK(newDek)
        prefs.edit()
            .putString(DEK_KEY, encrypted)
            .putString(DEK_IV_KEY, iv)
            .apply()
        return newDek
    }

    private fun generateDEK(): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES)
        keyGen.init(256)
        return keyGen.generateKey()
    }

    private fun encryptDEK(dek: SecretKey): Pair<String, String> {
        val masterKey = loadMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val encrypted = cipher.doFinal(dek.encoded)
        val iv = cipher.iv
        return Pair(
            android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP),
            android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
        )
    }

    private fun decryptDEK(encryptedB64: String, ivB64: String): SecretKey {
        val masterKey = loadMasterKey()
        val encrypted = android.util.Base64.decode(encryptedB64, android.util.Base64.NO_WRAP)
        val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
        val dekBytes = cipher.doFinal(encrypted)
        return SecretKeySpec(dekBytes, KeyProperties.KEY_ALGORITHM_AES)
    }

    private fun loadMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        return ks.getKey(KEYSTORE_MASTER_ALIAS, null) as SecretKey
    }
}
