package com.example.meetingtranscriber.network

import android.content.Context
import android.util.Log
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.db.SyncStateEntity
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.data.model.TranscriptSegment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CloudSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "CloudSyncManager"
    }

    private val db = AppDatabase.getInstance(context)
    private val gson = Gson()

    data class MeetingExport(
        val meeting: MeetingInfo,
        val segments: List<TranscriptSegment>
    )

    /** 导出会议为 JSON 文件（本地备份 + 云同步基础） */
    suspend fun exportMeeting(meetingId: Long): File? = withContext(Dispatchers.IO) {
        try {
            val meeting = db.meetingDao().getById(meetingId)?.let { entity ->
                MeetingInfo(
                    id = entity.id,
                    title = entity.title,
                    startTime = entity.startTime,
                    endTime = entity.endTime,
                    durationSeconds = entity.durationSeconds,
                    speakerCount = entity.speakerCount,
                    segmentCount = entity.segmentCount,
                    summary = entity.summary,
                    isOffline = entity.isOffline,
                    audioFilePath = entity.audioFilePath,
                    isArchived = entity.isArchived,
                    archivedAt = entity.archivedAt,
                    tag = entity.tag
                )
            } ?: return@withContext null

            val segments = db.transcriptDao().getSegmentsByMeetingOnce(meetingId)
                .filter { !it.isInterim }
                .map { entity ->
                    TranscriptSegment(
                        id = entity.id,
                        meetingId = entity.meetingId,
                        speakerId = entity.speakerId,
                        displaySpeaker = entity.displaySpeaker,
                        text = entity.text,
                        startTimeMs = entity.startTimeMs,
                        endTimeMs = entity.endTimeMs,
                        sentenceId = entity.sentenceId,
                        isInterim = entity.isInterim,
                        createdAt = entity.createdAt,
                        topicId = entity.topicId
                    )
                }

            val export = MeetingExport(meeting, segments)
            val dir = context.getExternalFilesDir("sync_exports") ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, "meeting_${meetingId}.json")
            file.writeText(gson.toJson(export), Charsets.UTF_8)

            // 标记为已同步
            db.syncStateDao().upsert(
                SyncStateEntity(meetingId = meetingId, lastSyncedAt = System.currentTimeMillis(), syncStatus = "synced")
            )

            Log.i(TAG, "会议 $meetingId 导出成功: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "导出失败: ${e.message}")
            null
        }
    }

    /** 导入会议从 JSON 文件 */
    suspend fun importMeeting(file: File): Long? = withContext(Dispatchers.IO) {
        try {
            val json = file.readText(Charsets.UTF_8)
            val export = gson.fromJson(json, MeetingExport::class.java)
            val meeting = export.meeting

            val entity = com.example.meetingtranscriber.data.db.MeetingEntity(
                title = meeting.title + " (已恢复)",
                startTime = meeting.startTime,
                endTime = meeting.endTime,
                durationSeconds = meeting.durationSeconds,
                speakerCount = meeting.speakerCount,
                segmentCount = meeting.segmentCount,
                summary = meeting.summary,
                isOffline = meeting.isOffline,
                audioFilePath = null,
                tag = meeting.tag
            )
            val newId = db.meetingDao().insert(entity)

            val segmentEntities = export.segments.map { seg ->
                com.example.meetingtranscriber.data.db.TranscriptEntity(
                    meetingId = newId,
                    speakerId = seg.speakerId,
                    displaySpeaker = seg.displaySpeaker,
                    text = seg.text,
                    startTimeMs = seg.startTimeMs,
                    endTimeMs = seg.endTimeMs,
                    sentenceId = seg.sentenceId,
                    isInterim = false,
                    createdAt = seg.createdAt,
                    topicId = seg.topicId
                )
            }
            db.transcriptDao().insertAll(segmentEntities)

            db.syncStateDao().upsert(
                SyncStateEntity(meetingId = newId, lastSyncedAt = System.currentTimeMillis(), syncStatus = "synced")
            )

            Log.i(TAG, "会议导入成功，新ID: $newId")
            newId
        } catch (e: Exception) {
            Log.e(TAG, "导入失败: ${e.message}")
            null
        }
    }

    /** 标记需要同步 */
    suspend fun markPending(meetingId: Long) {
        db.syncStateDao().upsert(
            SyncStateEntity(meetingId = meetingId, syncStatus = "pending")
        )
    }

    /** 获取未同步的会议数量 */
    suspend fun getPendingCount(): Int {
        return db.syncStateDao().getPendingSyncs().size
    }

    /** 同步所有待同步的会议 */
    suspend fun syncAll(): Int = withContext(Dispatchers.IO) {
        var count = 0
        val pending = db.syncStateDao().getPendingSyncs()
        for (state in pending) {
            if (exportMeeting(state.meetingId) != null) {
                count++
            }
        }
        count
    }

    /** 获取同步导出目录 */
    fun getExportDir(): File {
        val dir = context.getExternalFilesDir("sync_exports") ?: context.filesDir
        dir.mkdirs()
        return dir
    }

    /** 列出所有可导入的文件 */
    fun listImportFiles(): List<File> {
        val dir = getExportDir()
        return dir.listFiles()?.filter { it.extension == "json" }?.toList() ?: emptyList()
    }
}
