package com.example.meetingtranscriber.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.meetingtranscriber.security.CryptoManager
import net.sqlcipher.database.SupportFactory
import java.io.File

@Database(
    entities = [
        MeetingEntity::class,
        TranscriptEntity::class,
        RecoveryStateEntity::class,
        VocabularyEntity::class,
        VocabularyWordEntity::class,
        VocabularyMeetingCrossRef::class,
        SyncStateEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun meetingDao(): MeetingDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun recoveryStateDao(): RecoveryStateDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        private const val TAG = "AppDatabase"
        private const val DB_NAME = "meeting_transcriber.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null
        @Volatile private var migrationAttempted = false

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meetings ADD COLUMN isOffline INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meetings ADD COLUMN audioFilePath TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recovery_state (
                        meetingId INTEGER NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        startTime INTEGER NOT NULL,
                        isOfflineMode INTEGER NOT NULL DEFAULT 0,
                        isDemoMode INTEGER NOT NULL DEFAULT 0,
                        currentTaskId TEXT NOT NULL DEFAULT '',
                        audioBufferFilePath TEXT,
                        audioBufferFrameCount INTEGER NOT NULL DEFAULT 0,
                        speakerLabelMapJson TEXT NOT NULL DEFAULT '{}',
                        pendingSegmentsJson TEXT NOT NULL DEFAULT '[]',
                        lastSavedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS vocabulary (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        vocabularyId TEXT,
                        wordCount INTEGER NOT NULL DEFAULT 0,
                        sourceType TEXT NOT NULL DEFAULT 'manual',
                        createdTime INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS vocabulary_words (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        vocabularyId INTEGER NOT NULL,
                        word TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 1.0,
                        FOREIGN KEY (vocabularyId) REFERENCES vocabulary(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_vocab_words_vocab ON vocabulary_words(vocabularyId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS vocabulary_meeting_cross_ref (
                        vocabularyId INTEGER NOT NULL,
                        meetingId INTEGER NOT NULL,
                        PRIMARY KEY (vocabularyId, meetingId),
                        FOREIGN KEY (vocabularyId) REFERENCES vocabulary(id) ON DELETE CASCADE,
                        FOREIGN KEY (meetingId) REFERENCES meetings(id) ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN topicId INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meetings ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meetings ADD COLUMN archivedAt INTEGER")
                db.execSQL("ALTER TABLE meetings ADD COLUMN tag TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_state (
                        meetingId INTEGER NOT NULL PRIMARY KEY,
                        lastSyncedAt INTEGER NOT NULL DEFAULT 0,
                        syncStatus TEXT NOT NULL DEFAULT 'none'
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meetings ADD COLUMN asrEngineType TEXT")
                db.execSQL("ALTER TABLE meetings ADD COLUMN llmEngineType TEXT")
                db.execSQL("ALTER TABLE meetings ADD COLUMN dialectUsed TEXT")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_vocab_cross_ref_meeting ON vocabulary_meeting_cross_ref(meetingId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            INSTANCE?.let { return it }
            // 迁移必须在 synchronized 块外执行，因为 migratePlaintextIfNeeded 使用 runBlocking
            // 如果在 synchronized 内调用 runBlocking，DAO 操作可能触发同一把锁 → 死锁
            // migrationAttempted 标志防止多线程重复执行
            if (!migrationAttempted && CryptoManager.isEncryptionEnabled()) {
                migratePlaintextIfNeeded(context, context.getDatabasePath(DB_NAME))
                migrationAttempted = true
            }
            return synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val useEncryption = CryptoManager.isEncryptionEnabled()

            val builder = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

            if (useEncryption) {
                val passphrase = CryptoManager.getDatabasePassphrase()
                if (passphrase.isNotEmpty()) {
                    val factory = SupportFactory(passphrase)
                    builder.openHelperFactory(factory)
                    Log.i(TAG, "已启用 SQLCipher 数据库加密")
                } else {
                    Log.w(TAG, "加密已启用但密码为空，回退到明文模式")
                }
            }

            return builder.build()
        }

        /**
         * 明文 SQLite → 加密 SQLCipher 数据库迁移
         *
         * 对于已有明文数据的情况：
         * 1. 备份原 DB → .db.backup
         * 2. 用明文方式读取所有数据（meetings + transcript_segments）
         * 3. 删除旧明文 DB 及关联文件
         * 4. 通过 Room + SQLCipher SupportFactory 创建新加密 DB
         * 5. 通过 Room DAO 写回数据（避免直接操作 SupportSQLiteDatabase）
         */
        private fun migratePlaintextIfNeeded(context: Context, dbFile: File) {
            if (!dbFile.exists()) return

            try {
                val testDb = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                )
                val hasData: Boolean
                testDb.rawQuery("SELECT count(*) FROM meetings", null).use { cursor ->
                    hasData = cursor.moveToFirst() && cursor.getInt(0) > 0
                }
                testDb.close()
                if (!hasData) {
                    // 空数据库，直接删掉让 Room 重建加密版本
                    deleteDbFiles(dbFile)
                    return
                }
                Log.i(TAG, "检测到明文数据库，开始加密迁移...")
            } catch (e: Exception) {
                Log.i(TAG, "数据库可能已加密，跳过明文迁移")
                return
            }

            // 备份
            val backupFile = File(dbFile.parent, "$DB_NAME.backup")
            try {
                dbFile.copyTo(backupFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "数据库备份失败，取消迁移: ${e.message}")
                return
            }

            try {
                // 读取明文数据
                val oldDb = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
                )
                val meetingRows = readAllRows(oldDb, "meetings")
                val transcriptRows = readAllRows(oldDb, "transcript_segments")
                oldDb.close()

                // 删旧 DB
                deleteDbFiles(dbFile)

                // 通过 Room 创建加密 DB 并写回
                val passphrase = CryptoManager.getDatabasePassphrase()
                if (passphrase.isEmpty()) {
                    Log.w(TAG, "加密密码为空，跳过迁移")
                    backupFile.renameTo(dbFile)
                    return
                }

                val factory = SupportFactory(passphrase)
                val roomDb = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .openHelperFactory(factory)
                    .build()

                // 通过 DAO 写回数据
                kotlinx.coroutines.runBlocking {
                    for (row in meetingRows) {
                        val entity = rowToMeetingEntity(row)
                        if (entity != null) roomDb.meetingDao().insert(entity)
                    }
                    for (row in transcriptRows) {
                        val entity = rowToTranscriptEntity(row)
                        if (entity != null) roomDb.transcriptDao().insert(entity)
                    }
                }

                roomDb.close()
                backupFile.delete()
                Log.i(TAG, "明文数据库迁移成功：${meetingRows.size} 会议, ${transcriptRows.size} 转写片段")
            } catch (e: Exception) {
                Log.e(TAG, "数据库加密迁移失败: ${e.message}", e)
                deleteDbFiles(dbFile)
                backupFile.renameTo(dbFile)
                Log.w(TAG, "迁移失败，已回滚到明文数据库")
            }
        }

        private fun deleteDbFiles(dbFile: File) {
            dbFile.delete()
            dbFile.parentFile?.listFiles()?.filter {
                it.name.startsWith(DB_NAME)
            }?.forEach { it.delete() }
        }

        private fun readAllRows(db: SQLiteDatabase, table: String): List<Map<String, String?>> {
            val rows = mutableListOf<Map<String, String?>>()
            try {
                db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                    val columns = cursor.columnNames
                    while (cursor.moveToNext()) {
                        val row = mutableMapOf<String, String?>()
                        for (i in columns.indices) {
                            row[columns[i]] = if (cursor.isNull(i)) null else cursor.getString(i)
                        }
                        rows.add(row)
                    }
                }
            } catch (_: Exception) {}
            return rows
        }

        private fun rowToMeetingEntity(row: Map<String, String?>): MeetingEntity? {
            val id = row["id"]?.toLongOrNull() ?: return null
            return MeetingEntity(
                id = id,
                title = row["title"] ?: "未知会议",
                startTime = row["startTime"]?.toLongOrNull() ?: System.currentTimeMillis(),
                endTime = row["endTime"]?.toLongOrNull(),
                durationSeconds = row["durationSeconds"]?.toIntOrNull() ?: 0,
                speakerCount = row["speakerCount"]?.toIntOrNull() ?: 0,
                segmentCount = row["segmentCount"]?.toIntOrNull() ?: 0,
                summary = row["summary"],
                isOffline = (row["isOffline"]?.toIntOrNull() ?: 0) != 0,
                audioFilePath = row["audioFilePath"],
                asrEngineType = row["asrEngineType"],
                llmEngineType = row["llmEngineType"],
                dialectUsed = row["dialectUsed"]
            )
        }

        private fun rowToTranscriptEntity(row: Map<String, String?>): TranscriptEntity? {
            val id = row["id"]?.toLongOrNull() ?: return null
            return TranscriptEntity(
                id = id,
                meetingId = row["meetingId"]?.toLongOrNull() ?: 0,
                speakerId = row["speakerId"] ?: "",
                displaySpeaker = row["displaySpeaker"] ?: "",
                text = row["text"] ?: "",
                startTimeMs = row["startTimeMs"]?.toLongOrNull() ?: 0,
                endTimeMs = row["endTimeMs"]?.toLongOrNull() ?: 0,
                sentenceId = row["sentenceId"]?.toLongOrNull() ?: 0,
                isInterim = (row["isInterim"]?.toIntOrNull() ?: 0) != 0,
                createdAt = row["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                topicId = 0
            )
        }
    }
}
