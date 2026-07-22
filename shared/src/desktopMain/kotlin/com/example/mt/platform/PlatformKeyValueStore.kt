package com.example.mt.platform

import java.util.prefs.Preferences

/**
 * Desktop actual：java.util.prefs.Preferences（MVP 明文，Phase 2 加密）。
 *
 * API Key 敏感数据在 Phase 2 改用 AES-GCM 加密文件 + Windows DPAPI 密钥保护。
 */
actual class PlatformKeyValueStore actual constructor(private val name: String) {

    private val prefs: Preferences = Preferences.userRoot().node("com/example/mt/$name")

    actual fun getString(key: String, default: String): String =
        prefs.get(key, default)

    actual fun putString(key: String, value: String) {
        prefs.put(key, value)
        prefs.flush()
    }

    actual fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    actual fun putBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
        prefs.flush()
    }

    actual fun getLong(key: String, default: Long): Long =
        prefs.getLong(key, default)

    actual fun putLong(key: String, value: Long) {
        prefs.putLong(key, value)
        prefs.flush()
    }

    actual fun remove(key: String) {
        prefs.remove(key)
        prefs.flush()
    }

    actual fun clear() {
        prefs.clear()
        prefs.flush()
    }
}
