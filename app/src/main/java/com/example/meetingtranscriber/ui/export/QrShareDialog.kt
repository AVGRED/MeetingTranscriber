package com.example.meetingtranscriber.ui.export

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.meetingtranscriber.audio.AudioCacheManager
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.data.model.TranscriptSegment
import com.example.meetingtranscriber.databinding.DialogQrShareBinding
import com.example.meetingtranscriber.network.LanShareServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扫码下载对话框：准备文件（TXT/PDF/录音 WAV）→ 起局域网 HTTP 服务 → 展示二维码。
 * 服务生命周期与对话框绑定：dismiss 即 stop。
 */
object QrShareDialog {

    /** 跟踪活跃对话框对应的服务器，dismiss 时自动 stop */
    private val serverMap = java.util.WeakHashMap<AlertDialog, LanShareServer>()

    fun show(fragment: Fragment, meeting: MeetingInfo, segments: List<TranscriptSegment>) {
        val context = fragment.requireContext()
        if (LanShareServer.getLocalIpAddress() == null) {
            Toast.makeText(context, "请连接 WiFi 或开启热点后再试", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogQrShareBinding.inflate(fragment.layoutInflater)

        val dialog = AlertDialog.Builder(context)
            .setTitle("扫码下载「${meeting.title}」")
            .setView(binding.root)
            .setNegativeButton("关闭", null)
            .create()

        // 重试按钮
        binding.btnRetry.setOnClickListener {
            binding.layoutError.visibility = View.GONE
            binding.progressPrepare.visibility = View.VISIBLE
            prepareAndStart(fragment, context, meeting, segments, binding, dialog)
        }

        dialog.setOnDismissListener {
            serverMap.remove(dialog)?.stop()
        }
        dialog.show()

        prepareAndStart(fragment, context, meeting, segments, binding, dialog)
    }

    private fun prepareAndStart(
        fragment: Fragment,
        context: Context,
        meeting: MeetingInfo,
        segments: List<TranscriptSegment>,
        binding: DialogQrShareBinding,
        dialog: AlertDialog
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 准备分享文件（IO）
                val files = withContext(Dispatchers.IO) {
                    buildList {
                        ExportHelper.exportTxt(context, meeting, segments)?.let {
                            add(LanShareServer.SharedFile(
                                "record.txt", "${meeting.title}.txt", it, "text/plain; charset=utf-8"))
                        }
                        ExportHelper.exportPdf(context, meeting, segments)?.let {
                            add(LanShareServer.SharedFile(
                                "record.pdf", "${meeting.title}.pdf", it, "application/pdf"))
                        }
                        meeting.audioFilePath?.let { path ->
                            AudioCacheManager.getPlayableWav(context, path)?.let {
                                add(LanShareServer.SharedFile(
                                    "audio.wav", "${meeting.title}.wav", it, "audio/wav"))
                            }
                        }
                    }
                }

                if (files.isEmpty()) {
                    showError(binding, "文件准备失败，请检查存储空间")
                    return@launch
                }

                val server = withContext(Dispatchers.IO) {
                    LanShareServer.create(meeting.title, files)
                }

                if (server == null) {
                    showError(binding, "分享服务启动失败，请检查网络连接")
                    return@launch
                }

                if (!dialog.isShowing) {
                    server.stop()
                    return@launch
                }

                // 保存 server 引用以便 dismiss 时关闭
                serverMap[dialog] = server

                val url = server.rootUrl
                val qrBitmap = withContext(Dispatchers.Default) {
                    generateQrBitmap(url)
                }

                // 展示成功界面
                binding.progressPrepare.visibility = View.GONE
                binding.layoutQr.visibility = View.VISIBLE
                binding.layoutError.visibility = View.GONE
                binding.ivQr.setImageBitmap(qrBitmap)
                binding.tvUrl.text = url
                binding.tvUrl.visibility = View.VISIBLE
                binding.btnCopyUrl.visibility = View.VISIBLE
                binding.tvHint.visibility = View.VISIBLE

                // 复制网址按钮
                binding.btnCopyUrl.setOnClickListener {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("分享网址", url))
                    Toast.makeText(context, "网址已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }

            } catch (e: CancellationException) {
                serverMap.remove(dialog)?.stop()
                throw e
            } catch (e: Exception) {
                showError(binding, "发生错误: ${e.message ?: "未知"}")
            }
        }
    }

    private fun showError(binding: DialogQrShareBinding, message: String) {
        binding.progressPrepare.visibility = View.GONE
        binding.layoutQr.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE
        binding.tvErrorMsg.text = message
    }

    private fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
