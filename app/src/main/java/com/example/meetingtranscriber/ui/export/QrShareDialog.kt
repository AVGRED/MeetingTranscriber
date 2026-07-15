package com.example.meetingtranscriber.ui.export

import android.app.AlertDialog
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

    fun show(fragment: Fragment, meeting: MeetingInfo, segments: List<TranscriptSegment>) {
        val context = fragment.requireContext()
        if (LanShareServer.getLocalIpAddress() == null) {
            Toast.makeText(context, "请连接 WiFi 或开启热点后再试", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogQrShareBinding.inflate(fragment.layoutInflater)
        var server: LanShareServer? = null
        val dialog = AlertDialog.Builder(context)
            .setTitle("扫码下载「${meeting.title}」")
            .setView(binding.root)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnDismissListener { server?.stop() }
        dialog.show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 准备分享文件（IO）：文档复用现有导出（已含纪要+转写），录音转标准 WAV
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
                    dialog.dismiss()
                    Toast.makeText(context, "文件准备失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                server = withContext(Dispatchers.IO) {
                    LanShareServer.create(meeting.title, files)
                }
                val srv = server
                if (srv == null) {
                    dialog.dismiss()
                    Toast.makeText(context, "分享服务启动失败，请检查网络", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (!dialog.isShowing) {
                    // 用户在准备期间已关闭对话框
                    srv.stop()
                    return@launch
                }

                val url = srv.rootUrl
                val qrBitmap = withContext(Dispatchers.Default) { generateQrBitmap(url) }

                binding.progressPrepare.visibility = View.GONE
                binding.ivQr.setImageBitmap(qrBitmap)
                binding.ivQr.visibility = View.VISIBLE
                binding.tvUrl.text = url
                binding.tvUrl.visibility = View.VISIBLE
                binding.tvHint.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                // 页面销毁：确保已启动的服务被关闭
                server?.stop()
                throw e
            }
        }
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
