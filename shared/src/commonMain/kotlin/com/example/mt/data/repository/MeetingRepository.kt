package com.example.mt.data.repository

import com.example.mt.data.model.MeetingInfo
import com.example.mt.db.Meetings
import com.example.mt.db.MeetingsQueries
import com.example.mt.db.RecoveryStateQueries
import io.github.aakira.napier.Napier

/**
 * 会议仓库 — 封装 meetings 表和 recovery_state 的 CRUD。
 *
 * 依赖 SQLDelight 生成的 [MeetingsQueries] 和 [RecoveryStateQueries]，
 * 内部做生成类型 ↔ [MeetingInfo] 的映射。
 */
class MeetingRepository(
    private val meetingQueries: MeetingsQueries,
    private val recoveryQueries: RecoveryStateQueries,
) {

    // ═══════════════════════════════════════════════════
    // 写入
    // ═══════════════════════════════════════════════════

    fun insert(meeting: MeetingInfo): Long {
        meetingQueries.insertMeeting(
            title = meeting.title,
            startTime = meeting.startTime,
            endTime = meeting.endTime,
            durationSeconds = meeting.durationSeconds.toLong(),
            speakerCount = meeting.speakerCount.toLong(),
            segmentCount = meeting.segmentCount.toLong(),
            summary = meeting.summary,
            isOffline = if (meeting.isOffline) 1L else 0L,
            audioFilePath = meeting.audioFilePath,
            isArchived = if (meeting.isArchived) 1L else 0L,
            archivedAt = meeting.archivedAt,
            tag = meeting.tag,
            asrEngineType = meeting.asrEngineType,
            llmEngineType = meeting.llmEngineType,
            dialectUsed = meeting.dialectUsed,
        )
        return meetingQueries.lastInsertId().executeAsOne()
    }

    // ═══════════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════════

    fun getAll(): List<MeetingInfo> =
        meetingQueries.selectAllMeetings().executeAsList().map { it.toMeetingInfo() }

    fun getById(id: Long): MeetingInfo? =
        meetingQueries.selectMeetingById(id).executeAsOneOrNull()?.toMeetingInfo()

    fun getActive(): List<MeetingInfo> =
        meetingQueries.selectActiveMeetings().executeAsList().map { it.toMeetingInfo() }

    fun getArchived(): List<MeetingInfo> =
        meetingQueries.selectArchivedMeetings().executeAsList().map { it.toMeetingInfo() }

    fun getByTag(tag: String): List<MeetingInfo> =
        meetingQueries.selectMeetingsByTag(tag).executeAsList().map { it.toMeetingInfo() }

    fun search(query: String): List<MeetingInfo> =
        meetingQueries.searchMeetings(query).executeAsList().map { it.toMeetingInfo() }

    fun count(): Long = meetingQueries.countMeetings().executeAsOne()
    fun countActive(): Long = meetingQueries.countActiveMeetings().executeAsOne()

    // ═══════════════════════════════════════════════════
    // 更新
    // ═══════════════════════════════════════════════════

    fun updateTitle(id: Long, title: String) {
        meetingQueries.updateTitle(title, id)
    }

    fun updateSummary(id: Long, summary: String, llmEngineType: String?) {
        meetingQueries.updateSummary(summary, llmEngineType, id)
    }

    fun updateAudioPath(id: Long, path: String) {
        meetingQueries.updateAudioPath(path, id)
    }

    fun updateDialect(id: Long, dialect: String) {
        meetingQueries.updateDialect(dialect, id)
    }

    fun finishMeeting(
        id: Long,
        endTime: Long,
        durationSeconds: Int,
        speakerCount: Int,
        segmentCount: Int,
    ) {
        meetingQueries.finishMeeting(endTime, durationSeconds.toLong(), speakerCount.toLong(), segmentCount.toLong(), id)
    }

    fun archive(id: Long) {
        meetingQueries.archiveMeeting(System.currentTimeMillis(), id)
    }

    fun unarchive(id: Long) {
        meetingQueries.unarchiveMeeting(id)
    }

    // ═══════════════════════════════════════════════════
    // 删除
    // ═══════════════════════════════════════════════════

    fun delete(id: Long) {
        meetingQueries.deleteMeeting(id)
    }

    // ═══════════════════════════════════════════════════
    // 恢复状态
    // ═══════════════════════════════════════════════════

    fun saveRecoveryState(meetingId: Long, lastSentenceId: Long, lastSegmentId: Long, snapshot: String?) {
        recoveryQueries.upsertRecoveryState(
            meetingId = meetingId,
            lastSentenceId = lastSentenceId,
            lastSegmentId = lastSegmentId,
            rawSnapshot = snapshot,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun getRecoveryState(meetingId: Long): RecoveryState? =
        recoveryQueries.selectRecoveryState(meetingId).executeAsOneOrNull()?.let {
            RecoveryState(
                meetingId = it.meetingId,
                lastSentenceId = it.lastSentenceId,
                lastSegmentId = it.lastSegmentId,
                rawSnapshot = it.rawSnapshot,
            )
        }

    fun deleteRecoveryState(meetingId: Long) {
        recoveryQueries.deleteRecoveryState(meetingId)
    }

    fun deleteExpiredRecovery(beforeMs: Long) {
        recoveryQueries.deleteExpiredRecovery(beforeMs)
    }

    companion object {
        private const val TAG = "MeetingRepository"
    }
}

// ═══════════════════════════════════════════════════════════
// 映射扩展
// ═══════════════════════════════════════════════════════════

fun Meetings.toMeetingInfo() = MeetingInfo(
    id = id,
    title = title,
    startTime = startTime,
    endTime = endTime,
    durationSeconds = durationSeconds.toInt(),
    speakerCount = speakerCount.toInt(),
    segmentCount = segmentCount.toInt(),
    summary = summary,
    isOffline = isOffline != 0L,
    audioFilePath = audioFilePath,
    isArchived = isArchived != 0L,
    archivedAt = archivedAt,
    tag = tag,
    asrEngineType = asrEngineType,
    llmEngineType = llmEngineType,
    dialectUsed = dialectUsed,
)

/** 恢复状态快照 */
data class RecoveryState(
    val meetingId: Long,
    val lastSentenceId: Long,
    val lastSegmentId: Long,
    val rawSnapshot: String?,
)
