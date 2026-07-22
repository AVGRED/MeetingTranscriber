package com.example.mt.platform

/**
 * 平台键值存储接口（expect 声明）。
 *
 * - Android actual: EncryptedSharedPreferences + 明文 SharedPreferences
 * - Desktop actual: java.util.prefs.Preferences + AES-GCM 加密文件
 */
expect class PlatformKeyValueStore(name: String) {
    fun getString(key: String, default: String = ""): String
    fun putString(key: String, value: String)

    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)

    fun getLong(key: String, default: Long = 0L): Long
    fun putLong(key: String, value: Long)

    fun remove(key: String)
    fun clear()
}
