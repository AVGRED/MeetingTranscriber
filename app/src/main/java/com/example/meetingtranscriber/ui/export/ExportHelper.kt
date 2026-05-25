package com.example.meetingtranscriber.ui.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.data.model.TranscriptSegment
import java.io.File

object ExportHelper {

    fun exportTxt(context: Context, meeting: MeetingInfo, segments: List<TranscriptSegment>): File? {
        val content = buildTxtContent(meeting, segments)
        val fileName = sanitizeFileName("${meeting.title}.txt")
        val dir = context.getExternalFilesDir("exports") ?: return null
        val file = File(dir, fileName)
        dir.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    fun shareFile(context: Context, file: File, mimeType: String = "text/plain") {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享会议记录"))
    }

    private fun buildTxtContent(meeting: MeetingInfo, segments: List<TranscriptSegment>): String {
        return buildString {
            appendLine("=".repeat(50))
            appendLine(meeting.title)
            appendLine("时间: ${meeting.formattedStartTime}")
            appendLine("时长: ${meeting.formattedDuration}")
            appendLine("说话人数: ${meeting.speakerCount}")
            appendLine("发言条数: ${meeting.segmentCount}")
            appendLine("=".repeat(50))
            appendLine()

            if (!meeting.summary.isNullOrBlank()) {
                appendLine("【会议纪要】")
                appendLine(meeting.summary)
                appendLine()
            }

            appendLine("【会议转写】")
            appendLine()
            segments.forEach { segment ->
                appendLine("${segment.displaySpeaker} [${segment.formattedTime}]")
                appendLine(segment.text)
                appendLine()
            }

            appendLine("-".repeat(50))
            appendLine("由会议转写 App 生成")
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    }
}
