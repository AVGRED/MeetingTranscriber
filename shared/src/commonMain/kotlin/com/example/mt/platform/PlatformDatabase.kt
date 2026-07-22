package com.example.mt.platform

/**
 * 平台数据库接口（expect 声明）。
 *
 * 成员 C 负责使用 SQLDelight 定义 .sq 表并实现 actual：
 * - Android actual: android.driver (Android SQLite)
 * - Desktop actual: sqlite-driver (JDBC SQLite)
 */
expect class PlatformDatabase {
    /** 初始化/打开数据库 */
    fun open(): Boolean

    /** 关闭数据库 */
    fun close()

    /** 是否已打开 */
    val isOpen: Boolean
}
