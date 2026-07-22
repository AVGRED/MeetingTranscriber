package com.example.mt.network

import fi.iki.elonen.NanoHTTPD
import io.github.aakira.napier.Napier
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
 * 局域网分享服务（跨平台，NanoHTTPD 是纯 Java 库，桌面端可直接使用）。
 */
class LanShareServer private constructor(
    port: Int,
    private val token: String,
    private val meetingTitle: String,
    private val files: List<SharedFile>,
) : NanoHTTPD(port) {

    data class SharedFile(
        val urlName: String,
        val displayName: String,
        val file: File,
        val mime: String,
    )

    private val activeDownloads = AtomicInteger(0)

    companion object {
        private const val TAG = "LanShareServer"
        private const val PREFERRED_PORT = 8888
        private val FALLBACK_PORTS = intArrayOf(8889, 8890, 8891, 8892, 0)
        private val SECURE_RANDOM by lazy { SecureRandom() }

        fun create(meetingTitle: String, files: List<SharedFile>): LanShareServer? {
            val ip = getLocalIpAddress() ?: return null
            val token = ByteArray(8).let { bytes ->
                SECURE_RANDOM.nextBytes(bytes)
                bytes.joinToString("") { "%02x".format(it) }
            }

            val server = tryStart(token, meetingTitle, files) ?: return null
            server.hostIp = ip
            Napier.i("$TAG: 分享服务已启动: ${server.rootUrl}")
            return server
        }

        private fun tryStart(token: String, meetingTitle: String, files: List<SharedFile>): LanShareServer? {
            val ports = intArrayOf(PREFERRED_PORT, *FALLBACK_PORTS)
            for ((i, port) in ports.withIndex()) {
                try {
                    return LanShareServer(port, token, meetingTitle, files).apply {
                        start(SOCKET_READ_TIMEOUT, false)
                    }
                } catch (e: IOException) {
                    if (port == 0 && i == ports.lastIndex) {
                        Napier.e("$TAG: 所有端口均启动失败: ${e.message}")
                    } else {
                        Napier.w("$TAG: 端口 $port 被占用，尝试下一个")
                    }
                }
            }
            return null
        }

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

                val wlanV4 = candidates.firstOrNull { it.first.startsWith("wlan") && it.second is Inet4Address }
                if (wlanV4 != null) return wlanV4.second.hostAddress

                val anyV4 = candidates.firstOrNull { it.second is Inet4Address }
                if (anyV4 != null) return anyV4.second.hostAddress

                candidates.firstOrNull { it.second is Inet6Address && it.second.isSiteLocalAddress }
                    ?.let { return "[${it.second.hostAddress}]" }

                null
            } catch (e: Exception) {
                Napier.w("$TAG: 获取局域网 IP 失败: ${e.message}")
                null
            }
        }
    }

    private var hostIp: String = ""

    val rootUrl: String get() = "http://$hostIp:$listeningPort/$token/"

    val downloadCount: Int get() = activeDownloads.get()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        return when {
            uri == "/$token" -> {
                Napier.d("$TAG: 索引页请求 from ${session.remoteIpAddress}")
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", buildIndexHtml())
            }
            uri.startsWith("/$token/") -> {
                val name = uri.removePrefix("/$token/")
                val shared = files.firstOrNull { it.urlName == name }
                if (shared != null && shared.file.exists()) {
                    serveFile(shared)
                } else {
                    notFound()
                }
            }
            else -> notFound()
        }
    }

    override fun stop() {
        super.stop()
        Napier.i("$TAG: 分享服务已停止")
    }

    private fun serveFile(shared: SharedFile): Response {
        return try {
            activeDownloads.incrementAndGet()
            Napier.i("$TAG: 开始下载: ${shared.displayName}")
            val response = newFixedLengthResponse(
                Response.Status.OK, shared.mime,
                FileInputStream(shared.file), shared.file.length(),
            )
            val encoded = URLEncoder.encode(shared.displayName, "UTF-8").replace("+", "%20")
            response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encoded")
            response.addHeader("Cache-Control", "no-store")
            response.addHeader("Access-Control-Allow-Origin", "*")
            response
        } catch (e: IOException) {
            Napier.e("$TAG: 读取分享文件失败: ${e.message}")
            notFound()
        } finally {
            activeDownloads.decrementAndGet()
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "404 Not Found")

    private fun buildIndexHtml(): String = buildString {
        appendLine("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        appendLine("<meta name=\"color-scheme\" content=\"light dark\">")
        appendLine("<title>${escapeHtml(meetingTitle)}</title>")
        appendLine("<style>")
        appendLine(":root{color-scheme:light dark}")
        appendLine("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;margin:24px;color:#222;background:#fff}")
        appendLine("@media(prefers-color-scheme:dark){body{color:#e0e0e0;background:#1a1c1e}}")
        appendLine("h2{margin:0 0 4px;font-size:22px}")
        appendLine(".sub{color:#888;font-size:14px;margin-bottom:20px}")
        appendLine(".file-item{display:flex;align-items:center;padding:14px 16px;margin:8px 0;background:#f5f5f5;border-radius:10px;text-decoration:none}")
        appendLine("@media(prefers-color-scheme:dark){.file-item{background:#2a2a2e}}")
        appendLine(".file-name{color:#1565c0;font-size:16px;font-weight:500}")
        appendLine("@media(prefers-color-scheme:dark){.file-name{color:#90caf9}}")
        appendLine(".file-size{color:#888;font-size:13px;margin-top:2px}")
        appendLine("</style></head><body>")
        appendLine("<h2>📋 ${escapeHtml(meetingTitle)}</h2>")
        appendLine("<p class=\"sub\">点击文件名下载</p>")
        for (f in files) {
            val emoji = when {
                f.urlName.endsWith(".wav") -> "🎵"
                f.urlName.endsWith(".pdf") -> "📄"
                else -> "📝"
            }
            appendLine("<a class=\"file-item\" href=\"${f.urlName}\" download>")
            appendLine("<span>$emoji ${escapeHtml(f.displayName)}</span><br>")
            appendLine("<span class=\"file-size\">${formatSize(f.file.length())}</span></a>")
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
