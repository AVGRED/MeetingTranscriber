package com.example.meetingtranscriber.network

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

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

    /** 活跃下载计数（线程安全） */
    private val activeDownloads = AtomicInteger(0)

    companion object {
        private const val TAG = "LanShareServer"
        private const val PREFERRED_PORT = 8888

        /** 备选端口列表 */
        private val FALLBACK_PORTS = intArrayOf(8889, 8890, 8891, 8892, 0)

        /** 全局共享 SecureRandom 实例，避免每次创建熵源阻塞 */
        private val SECURE_RANDOM by lazy { SecureRandom() }

        /** 创建并返回未启动的服务 */
        fun create(meetingTitle: String, files: List<SharedFile>): LanShareServer? {
            val ip = getLocalIpAddress() ?: return null
            val token = ByteArray(8).let { bytes ->
                SECURE_RANDOM.nextBytes(bytes)
                bytes.joinToString("") { "%02x".format(it) }
            }

            val server = tryStart(token, meetingTitle, files)
                ?: return null
            server.hostIp = ip
            Log.i(TAG, "分享服务已启动: ${server.rootUrl}")
            return server
        }

        /** 依次尝试端口列表，返回第一个成功启动的服务 */
        private fun tryStart(
            token: String, meetingTitle: String, files: List<SharedFile>
        ): LanShareServer? {
            val ports = intArrayOf(PREFERRED_PORT, *FALLBACK_PORTS)
            for ((i, port) in ports.withIndex()) {
                try {
                    return LanShareServer(port, token, meetingTitle, files).apply {
                        start(SOCKET_READ_TIMEOUT, false)
                    }
                } catch (e: IOException) {
                    if (port == 0) {
                        // 0 = 系统随机端口，最后一次尝试也失败则放弃
                        if (i == ports.lastIndex) {
                            Log.e(TAG, "所有端口均启动失败: ${e.message}")
                        }
                    } else {
                        Log.w(TAG, "端口 $port 被占用，尝试下一个")
                    }
                }
            }
            return null
        }

        /**
         * 获取局域网 IP（优先 wlan 接口的 IPv4，其次任意 IPv4，最后 IPv6）。
         */
        fun getLocalIpAddress(): String? {
            return try {
                val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { nif ->
                        nif.inetAddresses.asSequence()
                            .filter { !it.isLoopbackAddress }
                            .map { nif.name to it }
                    }
                    .toList()

                // 优先级：wlan IPv4 > 任意 IPv4 > 任意 IPv6 site-local
                val wlanV4 = candidates.firstOrNull {
                    it.first.startsWith("wlan") && it.second is Inet4Address
                }
                if (wlanV4 != null) return wlanV4.second.hostAddress

                val anyV4 = candidates.firstOrNull { it.second is Inet4Address }
                if (anyV4 != null) return anyV4.second.hostAddress

                // IPv6 兜底（括号包裹以便在 URL 中使用）
                candidates.firstOrNull {
                    it.second is Inet6Address && it.second.isSiteLocalAddress
                }?.let {
                    return "[${it.second.hostAddress}]"
                }

                null
            } catch (e: Exception) {
                Log.w(TAG, "获取局域网 IP 失败: ${e.message}")
                null
            }
        }
    }

    private var hostIp: String = ""
    private var running: Boolean = true

    /** 扫码目标地址，start 成功后有效 */
    val rootUrl: String
        get() = "http://$hostIp:$listeningPort/$token/"

    /** 当前活跃下载数 */
    val downloadCount: Int get() = activeDownloads.get()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        return when {
            uri == "/$token" -> {
                Log.d(TAG, "索引页请求 from ${session.remoteIpAddress}")
                newFixedLengthResponse(
                    Response.Status.OK, "text/html; charset=utf-8", buildIndexHtml()
                )
            }
            uri.startsWith("/$token/") -> {
                val name = uri.removePrefix("/$token/")
                val shared = files.firstOrNull { it.urlName == name }
                if (shared != null && shared.file.exists()) {
                    serveFile(shared, session.remoteIpAddress)
                } else {
                    notFound()
                }
            }
            else -> notFound()
        }
    }

    override fun stop() {
        running = false
        super.stop()
        Log.i(TAG, "分享服务已停止")
    }

    private fun serveFile(shared: SharedFile, clientIp: String): Response {
        return try {
            activeDownloads.incrementAndGet()
            Log.i(TAG, "开始下载: ${shared.displayName} → $clientIp (${formatSize(shared.file.length())})")
            val response = newFixedLengthResponse(
                Response.Status.OK, shared.mime,
                FileInputStream(shared.file), shared.file.length()
            )
            val encoded = URLEncoder.encode(shared.displayName, "UTF-8").replace("+", "%20")
            response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encoded")
            response.addHeader("Cache-Control", "no-store")
            // 添加跨域头（方便 Web 页面 fetch）
            response.addHeader("Access-Control-Allow-Origin", "*")
            response
        } catch (e: IOException) {
            Log.e(TAG, "读取分享文件失败: ${e.message}")
            notFound()
        } finally {
            activeDownloads.decrementAndGet()
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(
            Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "404 Not Found"
        )

    private fun buildIndexHtml(): String = buildString {
        // 暗色模式感知：使用 prefers-color-scheme 媒体查询
        appendLine("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        appendLine("<meta name=\"color-scheme\" content=\"light dark\">")
        appendLine("<title>${escapeHtml(meetingTitle)}</title>")
        appendLine("<style>")
        appendLine(":root{color-scheme:light dark}")
        appendLine("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;" +
                "margin:24px;color:#222;background:#fff}")
        appendLine("@media(prefers-color-scheme:dark){body{color:#e0e0e0;background:#1a1c1e}}")
        appendLine("h2{margin:0 0 4px;font-size:22px}")
        appendLine(".sub{color:#888;font-size:14px;margin-bottom:20px}")
        appendLine("@media(prefers-color-scheme:dark){.sub{color:#999}}")
        appendLine(".file-list{list-style:none;padding:0}")
        appendLine(".file-item{display:flex;align-items:center;padding:14px 16px;margin:8px 0;" +
                "background:#f5f5f5;border-radius:10px;text-decoration:none;transition:background .15s}")
        appendLine("@media(prefers-color-scheme:dark){.file-item{background:#2a2a2e}}")
        appendLine(".file-item:hover{background:#e8e8e8}")
        appendLine("@media(prefers-color-scheme:dark){.file-item:hover{background:#3a3a3e}}")
        appendLine(".file-icon{font-size:24px;margin-right:14px;flex-shrink:0}")
        appendLine(".file-info{flex:1;min-width:0}")
        appendLine(".file-name{color:#1565c0;font-size:16px;font-weight:500;overflow:hidden;" +
                "text-overflow:ellipsis;white-space:nowrap}")
        appendLine("@media(prefers-color-scheme:dark){.file-name{color:#90caf9}}")
        appendLine(".file-size{color:#888;font-size:13px;margin-top:2px}")
        appendLine("@media(prefers-color-scheme:dark){.file-size{color:#999}}")
        appendLine(".footer{margin-top:24px;padding-top:16px;border-top:1px solid #e0e0e0;" +
                "color:#aaa;font-size:12px;text-align:center}")
        appendLine("@media(prefers-color-scheme:dark){.footer{border-top-color:#444;color:#777}}")
        appendLine("</style></head><body>")
        appendLine("<h2>📋 ${escapeHtml(meetingTitle)}</h2>")
        appendLine("<p class=\"sub\">点击文件名下载 · 下载完成前请保持此窗口打开</p>")
        appendLine("<div class=\"file-list\">")
        for (f in files) {
            val emoji = when {
                f.urlName.endsWith(".wav") -> "🎵"
                f.urlName.endsWith(".pdf") -> "📄"
                else -> "📝"
            }
            appendLine("<a class=\"file-item\" href=\"${f.urlName}\" download>")
            appendLine("<span class=\"file-icon\">$emoji</span>")
            appendLine("<span class=\"file-info\">")
            appendLine("<span class=\"file-name\">${escapeHtml(f.displayName)}</span>")
            appendLine("<span class=\"file-size\">${formatSize(f.file.length())}</span>")
            appendLine("</span></a>")
        }
        appendLine("</div>")
        appendLine("<p class=\"footer\">MeetingTranscriber · 文件通过局域网直接传输，安全私密</p>")
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
