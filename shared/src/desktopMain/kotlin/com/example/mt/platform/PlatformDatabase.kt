package com.example.mt.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.JdbcSqliteDriver
import com.example.mt.db.MeetingDatabase
import io.github.aakira.napier.Napier
import java.io.File

actual class PlatformDatabase actual constructor() {

    @Volatile
    private var driver: SqlDriver? = null
    @Volatile
    private var _database: MeetingDatabase? = null
    @Volatile
    private var opened = false
    private val openLock = Any()

    actual fun open(): Boolean {
        if (opened) return true

        synchronized(openLock) {
            // 双重检查：进入同步块后再次确认
            if (opened) return true

            val dbDir = System.getProperty("user.home") + "/MeetingTranscriber/data"
            File(dbDir).mkdirs()

            val dbPath = "$dbDir/meetings.db"
            val jdbcUrl = "jdbc:sqlite:${dbPath.replace(" ", "%20")}"

            val newDriver = try {
                JdbcSqliteDriver(jdbcUrl).also { d ->
                    MeetingDatabase.Schema.create(d)
                    d.execute(null, "PRAGMA foreign_keys = ON", 0)
                }
            } catch (e: Exception) {
                Napier.e("Failed to create/open database", e)
                return false
            }

            driver = newDriver
            _database = MeetingDatabase(newDriver)
            opened = true
            return true
        }
    }

    actual fun close() {
        synchronized(openLock) {
            try { driver?.close() } catch (e: Exception) { Napier.w("Error closing database", e) }
            driver = null
            _database = null
            opened = false
        }
    }

    actual val isOpen: Boolean get() = opened

    /** 供 Repository 使用，需在 open() 之后调用 */
    val meetingQueries get() = database.meetingQueries
    val transcriptQueries get() = database.transcriptSegmentsQueries
    val recoveryQueries get() = database.recoveryStateQueries

    private val database: MeetingDatabase
        get() = _database
            ?: throw IllegalStateException(
                if (opened) "数据库已关闭，请先调用 open()" else "数据库未打开，请先调用 open()"
            )
}
