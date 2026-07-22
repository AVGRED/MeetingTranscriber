package com.example.mt.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.JdbcSqliteDriver
import com.example.mt.db.MeetingDatabase
import java.io.File

actual class PlatformDatabase actual constructor() {

    private var driver: SqlDriver? = null
    private var _database: MeetingDatabase? = null
    private var opened = false

    actual fun open(): Boolean {
        if (opened) return true

        val dbDir = System.getProperty("user.home") + "/MeetingTranscriber/data"
        File(dbDir).mkdirs()

        val dbPath = "$dbDir/meetings.db"
        val jdbcUrl = "jdbc:sqlite:$dbPath"

        driver = JdbcSqliteDriver(jdbcUrl).also { d ->
            MeetingDatabase.Schema.create(d)
            d.execute(null, "PRAGMA foreign_keys = ON", 0)
        }

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

    /** 供 Repository 使用，需在 open() 之后调用 */
    val meetingQueries get() = database.meetingQueries
    val transcriptQueries get() = database.transcriptSegmentsQueries
    val recoveryQueries get() = database.recoveryStateQueries

    private val database: MeetingDatabase
        get() = _database ?: throw IllegalStateException("数据库未打开，请先调用 open()")
}
