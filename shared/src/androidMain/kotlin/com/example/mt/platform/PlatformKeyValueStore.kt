package com.example.mt.platform

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android actual：加密存储 API Key + 明文存储普通偏好。
 *
 * 桥接回原 PreferencesManager 的 secure/plain 双存储逻辑。
 */
actual class PlatformKeyValueStore actual constructor(private val name: String) {

    private val ctx: Context get() = getAppContext()
        ?: throw IllegalStateException("Application Context 未注入，请在 Application.onCreate 中调用 setAppContext()")

    private val securePrefs: SharedPreferences by lazy { obtainSecurePrefs() }

    private val plainPrefs: SharedPreferences by lazy {
        ctx.getSharedPreferences("${name}_plain", Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedSecurePrefs: SharedPreferences? = null

    private fun obtainSecurePrefs(): SharedPreferences {
        cachedSecurePrefs?.let { return it }
        synchronized(this) {
            cachedSecurePrefs?.let { return it }
            val prefs = try {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    ctx, "${name}_secure", masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w("PlatformKeyValueStore",
                    "EncryptedSharedPreferences 初始化失败，API Key 将以非加密方式存储", e)
                // 使用独立的名字避免与加密存储冲突（防止加密恢复后数据"消失"）
                ctx.getSharedPreferences("${name}_secure_fallback_plaintext", Context.MODE_PRIVATE)
            }
            cachedSecurePrefs = prefs
            return prefs
        }
    }

    actual fun getString(key: String, default: String): String =
        securePrefs.getString(key, null) ?: plainPrefs.getString(key, default) ?: default

    actual fun putString(key: String, value: String) =
        securePrefs.edit().putString(key, value).apply()

    actual fun getBoolean(key: String, default: Boolean): Boolean =
        plainPrefs.getBoolean(key, default)

    actual fun putBoolean(key: String, value: Boolean) =
        plainPrefs.edit().putBoolean(key, value).apply()

    actual fun getLong(key: String, default: Long): Long =
        plainPrefs.getLong(key, default)

    actual fun putLong(key: String, value: Long) =
        plainPrefs.edit().putLong(key, value).apply()

    actual fun remove(key: String) {
        securePrefs.edit().remove(key).apply()
        plainPrefs.edit().remove(key).apply()
    }

    actual fun clear() {
        securePrefs.edit().clear().apply()
        plainPrefs.edit().clear().apply()
    }
}
