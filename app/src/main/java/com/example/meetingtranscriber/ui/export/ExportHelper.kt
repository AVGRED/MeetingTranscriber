package com.example.meetingtranscriber.ui.export

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.data.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ExportHelper {

    private fun resolveExportDir(context: Context): File? {
        return context.getExternalFilesDir("exports")
            ?: context.getExternalFilesDir(null)?.let { File(it, "exports").also { it.mkdirs() } }
    }

    fun exportTxt(context: Context, meeting: MeetingInfo, segments: List<TranscriptSegment>): File? {
        val content = buildTxtContent(meeting, segments)
        val fileName = sanitizeFileName("${meeting.title}.txt")
        val dir = resolveExportDir(context) ?: return null
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    fun exportWord(context: Context, meeting: MeetingInfo, segments: List<TranscriptSegment>): File? {
        val html = buildHtmlContent(meeting, segments)
        val fileName = sanitizeFileName("${meeting.title}.doc")
        val dir = resolveExportDir(context) ?: return null
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(html, Charsets.UTF_8)
        return file
    }

    fun exportPdf(context: Context, meeting: MeetingInfo, segments: List<TranscriptSegment>): File? {
        val fileName = sanitizeFileName("${meeting.title}.pdf")
        val dir = resolveExportDir(context) ?: return null
        dir.mkdirs()
        val file = File(dir, fileName)

        val document = PdfDocument()
        val titlePaint = Paint().apply {
            textSize = 20f; isFakeBoldText = true; typeface = Typeface.DEFAULT_BOLD
        }
        val subtitlePaint = Paint().apply {
            textSize = 11f; color = 0xFF888888.toInt()
        }
        val bodyPaint = Paint().apply {
            textSize = 12f; isAntiAlias = true
        }
        val speakerPaint = Paint().apply {
            textSize = 12f; isFakeBoldText = true; color = 0xFF1565C0.toInt()
        }
        val sectionPaint = Paint().apply {
            textSize = 14f; isFakeBoldText = true
        }

        val pageWidth = 595
        var y = 40f
        val margin = 40f
        val contentWidth = pageWidth - margin * 2
        val lineHeight = 18f

        fun Canvas.drawWrapped(text: String, paint: Paint, maxWidth: Float): Float {
            var currentY = 0f
            val lines = text.split("\n")
            for (line in lines) {
                if (line.isEmpty()) {
                    currentY += lineHeight
                    continue
                }
                val chars = paint.breakText(line, true, maxWidth, null)
                if (chars == line.length) {
                    drawText(line, margin, y + currentY + paint.textSize, paint)
                    currentY += lineHeight * 1.2f
                } else {
                    var remaining = line
                    while (remaining.isNotEmpty()) {
                        val n = paint.breakText(remaining, true, maxWidth, null)
                        drawText(remaining.substring(0, n), margin, y + currentY + paint.textSize, paint)
                        currentY += lineHeight * 1.2f
                        remaining = remaining.substring(n)
                    }
                }
            }
            return currentY
        }

        fun Canvas.newPage(): PdfDocument.Page {
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth.toInt(), 842, 1).create())
            y = 40f
            return page
        }

        try {
            var page = document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth.toInt(), 842, 1).create()
            )
            var canvas: Canvas = page.canvas

            canvas.drawText(meeting.title, margin, y + titlePaint.textSize, titlePaint)
            y += 30f
            canvas.drawText("${meeting.formattedStartTime}  ${meeting.formattedDuration}  ${meeting.speakerCount}位说话人",
                margin, y + subtitlePaint.textSize, subtitlePaint)
            y += 30f

            if (!meeting.summary.isNullOrBlank()) {
                y += 10f
                canvas.drawText("【会议纪要】", margin, y + sectionPaint.textSize, sectionPaint)
                y += lineHeight * 1.5f
                val h = canvas.drawWrapped(meeting.summary, bodyPaint, contentWidth)
                y += h + 10f
            }

            y += 10f
            canvas.drawText("【会议转写】", margin, y + sectionPaint.textSize, sectionPaint)
            y += lineHeight * 1.5f

            val sorted = segments.sortedBy { it.startTimeMs }
            var currentTopic = -1
            for (segment in sorted) {
                if (segment.topicId != 0 && segment.topicId != currentTopic) {
                    currentTopic = segment.topicId
                    val topicCount = sorted.count { it.topicId == currentTopic }
                    if (y > 760f) {
                        document.finishPage(page)
                        page = canvas.newPage()
                        canvas = page.canvas
                    }
                    canvas.drawText("── 话题 $currentTopic  ($topicCount 条发言) ──",
                        margin, y + sectionPaint.textSize, sectionPaint)
                    y += lineHeight * 1.5f
                }

                if (y > 780f) {
                    document.finishPage(page)
                    page = canvas.newPage()
                    canvas = page.canvas
                }

                val speakerLine = "${segment.displaySpeaker} [${segment.formattedTime}]"
                canvas.drawText(speakerLine, margin, y + speakerPaint.textSize, speakerPaint)
                y += lineHeight * 1.2f

                val h = canvas.drawWrapped(segment.text, bodyPaint, contentWidth)
                y += h + 6f
            }

            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }

        return file
    }

    /**
     * 显示导出弹窗（统一的导出入口，供 DetailFragment & HistoryFragment 共用）。
     *
     * @param fragment 宿主 Fragment（需要 lifecycleScope）
     * @param meeting  会议信息
     * @param segments 转写片段列表
     */
    fun showExportDialog(
        fragment: Fragment,
        meeting: MeetingInfo,
        segments: List<TranscriptSegment>
    ) {
        val ctx = fragment.requireContext()
        if (segments.isEmpty()) {
            Toast.makeText(ctx, "暂无转写内容", Toast.LENGTH_SHORT).show()
            return
        }

        val formats = arrayOf("TXT 文本", "Word 文档", "PDF 文件", "扫码下载（文档+录音）")
        val mimeTypes = arrayOf("text/plain", "application/msword", "application/pdf")

        AlertDialog.Builder(ctx)
            .setTitle("导出「${meeting.title}」")
            .setItems(formats) { _, which ->
                if (which == 3) {
                    QrShareDialog.show(fragment, meeting, segments)
                    return@setItems
                }
                fragment.lifecycleScope.launch {
                    val file = withContext(Dispatchers.IO) {
                        when (which) {
                            0 -> exportTxt(ctx, meeting, segments)
                            1 -> exportWord(ctx, meeting, segments)
                            2 -> exportPdf(ctx, meeting, segments)
                            else -> null
                        }
                    }
                    if (file != null) {
                        AlertDialog.Builder(ctx)
                            .setTitle("导出成功")
                            .setMessage("已保存到:\n${file.absolutePath}")
                            .setPositiveButton("分享") { _, _ ->
                                shareFile(ctx, file, mimeTypes[which])
                            }
                            .setNegativeButton("关闭", null)
                            .show()
                    } else {
                        Toast.makeText(ctx, "导出失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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

    // ─── content builders ───

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

            writeSegmentsWithTopics(segments) { header, segment ->
                if (header != null) {
                    appendLine(header)
                    appendLine()
                }
                if (segment != null) {
                    appendLine("${segment.displaySpeaker} [${segment.formattedTime}]")
                    appendLine(segment.text)
                    appendLine()
                }
            }

            appendLine("-".repeat(50))
            appendLine("由会议转写 App 生成")
        }
    }

    private fun buildHtmlContent(meeting: MeetingInfo, segments: List<TranscriptSegment>): String {
        return buildString {
            appendLine("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            appendLine("<title>${meeting.title}</title>")
            appendLine("<style>body{font-family:'Microsoft YaHei',sans-serif;max-width:700px;margin:40px auto;padding:0 16px;color:#333;line-height:1.8}")
            appendLine("h1{font-size:22px;border-bottom:2px solid #1565C0;padding-bottom:8px}")
            appendLine(".meta{color:#888;font-size:13px;margin-bottom:20px}")
            appendLine(".summary{background:#f5f5f5;padding:16px;border-radius:8px;margin:16px 0}")
            appendLine(".speaker{color:#1565C0;font-weight:bold}.time{color:#999;font-size:12px}")
            appendLine(".topic{color:#1565C0;font-size:15px;font-weight:bold;margin:24px 0 8px 0;border-bottom:1px solid #e0e0e0;padding-bottom:4px}")
            appendLine("p{margin:4px 0 12px 0}</style></head><body>")

            appendLine("<h1>${meeting.title}</h1>")
            appendLine("<div class=\"meta\">${meeting.formattedStartTime} · ${meeting.formattedDuration} · ${meeting.speakerCount}位说话人 · ${meeting.segmentCount}条发言</div>")

            if (!meeting.summary.isNullOrBlank()) {
                appendLine("<div class=\"summary\"><strong>会议纪要</strong><br>${meeting.summary}</div>")
            }

            appendLine("<h2>会议转写</h2>")

            writeSegmentsWithTopics(segments) { header, segment ->
                if (header != null) {
                    appendLine("<div class=\"topic\">$header</div>")
                }
                if (segment != null) {
                    appendLine("<p><span class=\"speaker\">${segment.displaySpeaker}</span> <span class=\"time\">[${segment.formattedTime}]</span><br>")
                    appendLine("${segment.text}</p>")
                }
            }

            appendLine("<hr><small>由会议转写 App 生成</small>")
            appendLine("</body></html>")
        }
    }

    /**
     * 按时间排序后遍历片段，当 topicId 变化时先回调话题标题再回调片段。
     * header 为 null 表示普通片段行，segment 为 null 表示仅标题行。
     */
    private fun writeSegmentsWithTopics(
        segments: List<TranscriptSegment>,
        onItem: (header: String?, segment: TranscriptSegment?) -> Unit
    ) {
        val sorted = segments.sortedBy { it.startTimeMs }
        var currentTopic = -1
        for (seg in sorted) {
            if (seg.topicId != 0 && seg.topicId != currentTopic) {
                currentTopic = seg.topicId
                val count = sorted.count { it.topicId == currentTopic }
                onItem("── 话题 $currentTopic  ($count 条发言) ──", null)
            }
            onItem(null, seg)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    }
}
