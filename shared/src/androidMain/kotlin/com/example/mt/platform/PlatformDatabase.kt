package com.example.mt.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.mt.db.MeetingDatabase

actual class PlatformDatabase actual constructor() {

    private var driver: SqlDriver? = null
    private var _database: MeetingDatabase? = null
    private var opened = false

    actual fun open(): Boolean {
        if (opened) return true

        val context = getAppContext()
            ?: throw IllegalStateException("Android AppContext 未注入，请先调用 setAppContext()")

        driver = AndroidSqliteDriver(MeetingDatabase.Schema, context, "meetings.db")
        _database = MeetingDatabase(driver!!)
        opened = true
        return true
    }

    actual fun close() {
        driver?.close()
        driver = null
        _database = null
        opened = false
    }

    actual val isOpen: Boolean get() = opened

    val meetingQueries get() = database.meetingQueries
    val transcriptQueries get() = database.transcriptSegmentsQueries
    val recoveryQueries get() = database.recoveryStateQueries

    private val database: MeetingDatabase
        get() = _database ?: throw IllegalStateException("数据库未打开，请先调用 open()")
}
