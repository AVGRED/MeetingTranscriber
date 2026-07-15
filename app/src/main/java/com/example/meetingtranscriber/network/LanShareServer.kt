package com.example.meetingtranscriber.network

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * 局域网分享服务：同 WiFi/热点设备扫码后从手机直接下载会议记录与录音。
 *
 * 路由（token = 每次分享随机生成的 16 位 hex，防局域网嗅探）：
 * - GET /{token}/            → 索引 HTML（会议标题 + 各文件下载链接）
 * - GET /{token}/{urlName}   → 文件流下载
 * - 其他                     → 404
 *
 * 生命周期与二维码对话框绑定：对话框显示时 start，dismiss 时 stop。
 */
class LanShareServer private constructor(
    port: Int,
    private val token: String,
    private val meetingTitle: String,
    private val files: List<SharedFile>
) : NanoHTTPD(port) {

    data class SharedFile(
        val urlName: String,      // URL 路径名（ASCII，如 record.txt）
        val displayName: String,  // 下载文件名（可中文）
        val file: File,
        val mime: String
    )

    companion object {
        private const val TAG = "LanShareServer"
        private const val PREFERRED_PORT = 8888

        /** 创建并返回未启动的服务；拿不到局域网 IP 时返回 null */
        fun create(meetingTitle: String, files: List<SharedFile>): LanShareServer? {
            val ip = getLocalIpAddress() ?: return null
            val token = ByteArray(8).let { bytes ->
                SecureRandom().nextBytes(bytes)
                bytes.joinToString("") { "%02x".format(it) }
            }
            // 先试固定端口，被占用则让系统随机分配
            val server = try {
                LanShareServer(PREFERRED_PORT, token, meetingTitle, files).apply {
                    start(SOCKET_READ_TIMEOUT, false)
                }
            } catch (e: IOException) {
                Log.w(TAG, "端口 $PREFERRED_PORT 被占用，改用随机端口")
                try {
                    LanShareServer(0, token, meetingTitle, files).apply {
                        start(SOCKET_READ_TIMEOUT, false)
                    }
                } catch (e2: IOException) {
                    Log.e(TAG, "启动分享服务失败: ${e2.message}")
                    return null
                }
            }
            server.hostIp = ip
            return server
        }

        /**
         * 枚举网卡取局域网 IPv4，wlan* 接口优先（同时覆盖 WiFi 和热点场景）。
         * 不用 ConnectivityManager.activeNetwork：开热点时 activeNetwork 是蜂窝网，拿不到 192.168.43.x。
         */
        fun getLocalIpAddress(): String? {
            return try {
                val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { nif ->
                        nif.inetAddresses.asSequence()
                            .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
                            .map { nif.name to it.hostAddress }
                    }
                    .toList()
                (candidates.firstOrNull { it.first.startsWith("wlan") } ?: candidates.firstOrNull())
                    ?.second
            } catch (e: Exception) {
                Log.w(TAG, "获取局域网 IP 失败: ${e.message}")
                null
            }
        }
    }

    private var hostIp: String = ""

    /** 扫码目标地址，start 成功后有效 */
    val rootUrl: String
        get() = "http://$hostIp:$listeningPort/$token/"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        return when {
            uri == "/$token" -> newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8", buildIndexHtml()
            )
            uri.startsWith("/$token/") -> {
                val name = uri.removePrefix("/$token/")
                val shared = files.firstOrNull { it.urlName == name }
                if (shared != null && shared.file.exists()) serveFile(shared) else notFound()
            }
            else -> notFound()
        }
    }

    private fun serveFile(shared: SharedFile): Response {
        return try {
            val response = newFixedLengthResponse(
                Response.Status.OK, shared.mime, FileInputStream(shared.file), shared.file.length()
            )
            // RFC 5987：中文文件名 URL 编码
            val encoded = URLEncoder.encode(shared.displayName, "UTF-8").replace("+", "%20")
            response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encoded")
            response
        } catch (e: IOException) {
            Log.e(TAG, "读取分享文件失败: ${e.message}")
            notFound()
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "404 Not Found")

    private fun buildIndexHtml(): String = buildString {
        appendLine("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        appendLine("<title>${escapeHtml(meetingTitle)}</title>")
        appendLine("<style>body{font-family:sans-serif;margin:24px;color:#222}")
        appendLine("h2{margin-bottom:4px}p{color:#888;font-size:14px}")
        appendLine("a{display:block;padding:14px 16px;margin:10px 0;background:#f5f5f5;")
        appendLine("border-radius:8px;text-decoration:none;color:#1565c0;font-size:16px}</style></head><body>")
        appendLine("<h2>${escapeHtml(meetingTitle)}</h2>")
        appendLine("<p>点击下载 · 下载完成前请保持手机上的分享窗口打开</p>")
        for (f in files) {
            val size = formatSize(f.file.length())
            appendLine("<a href=\"${f.urlName}\" download>${escapeHtml(f.displayName)}（$size）</a>")
        }
        appendLine("</body></html>")
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
