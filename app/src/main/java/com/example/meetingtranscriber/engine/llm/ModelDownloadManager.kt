package com.example.meetingtranscriber.engine.llm

import android.content.Context
import android.util.Log
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Qwen GGUF 模型下载管理器。
 *
 * 首次启动时下载 Qwen-2-1.5B GGUF Q4_K_M (~1.1GB) 到本地存储。
 * 支持断点续传（HTTP Range），下载进度实时更新。
 *
 * ### 使用
 * ```kotlin
 * val manager = ModelDownloadManager(context)
 * manager.downloadProgress.collect { state -> updateUI(state) }
 * val result = manager.download()
 * ```
 *
 * ### 模型来源
 * 默认使用 ModelScope（国内更快），可通过 [downloadUrl] 参数指定其他源。
 * Hugging Face 镜像: https://huggingface.co/Qwen/Qwen2-1.5B-Instruct-GGUF/resolve/main/
 */
class ModelDownloadManager(private val context: Context) {

    /**
     * 下载状态。
     */
    data class DownloadState(
        val status: Status,
        val progress: Float = 0f,        // 0→1
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val speedMbps: Float = 0f,        // 当前速度 MB/s
        val errorMessage: String? = null
    ) {
        enum class Status { IDLE, DOWNLOADING, PAUSED, VERIFYING, COMPLETED, ERROR }

        val downloadedMB: Float get() = downloadedBytes / (1024f * 1024f)
        val totalMB: Float get() = totalBytes / (1024f * 1024f)
        val percent: Int get() = if (totalBytes > 0) ((progress * 100).toInt()) else 0
    }

    private val _downloadProgress = MutableStateFlow(DownloadState(DownloadState.Status.IDLE))
    val downloadProgress: StateFlow<DownloadState> = _downloadProgress

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var downloadJob: Job? = null

    /** 目标模型文件（纯路径计算，getter 会在主线程被触碰，不做磁盘写） */
    val modelFile: File
        get() = File(File(context.filesDir, QwenEngine.MODEL_DIR), QwenEngine.MODEL_FILE_NAME)

    /** 临时下载文件（断点续传用） */
    private val tempFile: File
        get() = File(modelFile.absolutePath + ".tmp")

    /**
     * 检查模型是否已下载完成。
     */
    fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE

    /**
     * 删除已下载的模型（释放存储空间）。
     */
    fun deleteModel(): Boolean {
        val deleted = modelFile.delete()
        tempFile.delete()
        if (deleted) Log.i(TAG, "模型文件已删除: ${modelFile.absolutePath}")
        return deleted
    }

    /**
     * 开始下载（挂起函数，下载完成或失败后返回）。
     */
    suspend fun download(downloadUrl: String = DEFAULT_URL): Result<File> {
        // 捕获协程 Job 以支持 cancel()
        downloadJob = coroutineContext[Job]
        return withContext(Dispatchers.IO) {
            modelFile.parentFile?.mkdirs() // 目录创建移到下载路径（原在 getter 副作用里）
            if (isModelDownloaded()) {
                Log.i(TAG, "模型已存在，跳过下载: ${modelFile.absolutePath}")
                _downloadProgress.value = DownloadState(
                    DownloadState.Status.COMPLETED,
                    progress = 1f,
                    downloadedBytes = modelFile.length(),
                    totalBytes = modelFile.length()
                )
                return@withContext Result.success(modelFile)
            }

            // 检查存储空间
            val freeSpace = modelFile.parentFile?.usableSpace ?: 0
            if (freeSpace < REQUIRED_SPACE) {
                val msg = "存储空间不足: 需要 ${REQUIRED_SPACE / (1024 * 1024)}MB，可用 ${freeSpace / (1024 * 1024)}MB"
                Log.e(TAG, msg)
                _downloadProgress.value = DownloadState(DownloadState.Status.ERROR, errorMessage = msg)
                return@withContext Result.failure(IOException(msg))
            }

            try {
                downloadWithResume(downloadUrl)
            } catch (e: CancellationException) {
                Log.i(TAG, "下载已取消（临时文件保留: ${tempFile.length()} bytes）")
                _downloadProgress.value = DownloadState(DownloadState.Status.PAUSED,
                    errorMessage = "下载已暂停")
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "下载网络错误: ${e.message}", e)
                _downloadProgress.value = DownloadState(DownloadState.Status.ERROR,
                    errorMessage = "网络错误: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "下载异常: ${e.message}", e)
                _downloadProgress.value = DownloadState(DownloadState.Status.ERROR,
                    errorMessage = "下载失败: ${e.message}")
                Result.failure(e)
            }
        }
    }

    /**
     * 取消下载。
     */
    fun cancel() {
        downloadJob?.cancel()
        Log.i(TAG, "下载已取消")
    }

    // ═══════════════════════════════════════════════════════════
    // 内部实现
    // ═══════════════════════════════════════════════════════════

    /**
     * 带断点续传的下载。
     *
     * 流程:
     * 1. 检查 tempFile 已有字节数
     * 2. 如果 >0 → HEAD 请求获取文件大小 + ETag
     * 3. GET + Range: bytes={offset}-
     * 4. 流式追加写入 tempFile
     * 5. 下载完成 → 校验大小 → 重命名为正式文件名
     */
    @Throws(IOException::class)
    private fun downloadWithResume(url: String): Result<File> {
        var downloadedBytes = 0L
        if (tempFile.exists()) {
            downloadedBytes = tempFile.length()
            Log.i(TAG, "续传起点: ${downloadedBytes / (1024 * 1024)}MB")
        }

        // ── HEAD 请求获取远程文件大小 ──
        val headRequest = Request.Builder().url(url).head().build()
        var totalBytes = 0L
        var etag = ""
        client.newCall(headRequest).execute().use { resp ->
            totalBytes = resp.header("Content-Length")?.toLongOrNull() ?: 0L
            etag = resp.header("ETag") ?: ""

            if (totalBytes <= 0) {
                return Result.failure(IOException("无法获取远程文件大小"))
            }
            if (totalBytes < MIN_MODEL_SIZE) {
                return Result.failure(IOException("远程文件异常: 大小 ${totalBytes / (1024 * 1024)}MB < 预期"))
            }

            Log.i(TAG, "远程文件: ${totalBytes / (1024 * 1024)}MB, ETag=$etag")
        }

        // 已下载完成 → 直接校验
        if (downloadedBytes >= totalBytes) {
            Log.i(TAG, "临时文件已完整，跳过下载")
            return verifyAndFinalize(tempFile, totalBytes)
        }

        // ── GET 下载（Range 断点续传） ──
        val getRequest = Request.Builder()
            .url(url)
            .apply {
                if (downloadedBytes > 0 && etag.isNotBlank()) {
                    header("Range", "bytes=$downloadedBytes-")
                    header("If-Range", etag)
                }
            }
            .build()

        val response = client.newCall(getRequest).execute()

        if (!response.isSuccessful && response.code != 206) {
            return Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
        }

        // 如果服务器不支持续传，从头开始
        val isResume = response.code == 206
        if (!isResume && downloadedBytes > 0) {
            Log.w(TAG, "服务器不支持续传，从头下载")
            tempFile.delete()
            downloadedBytes = 0
        }

        val body = response.body ?: return Result.failure(IOException("响应 body 为空"))

        // ── 流式写入 ──
        val startTime = System.currentTimeMillis()
        var lastLogTime = startTime
        var lastLogBytes = downloadedBytes

        body.byteStream().use { input ->
            FileOutputStream(tempFile, downloadedBytes > 0).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read = input.read(buffer)
                while (read > 0) {
                    output.write(buffer, 0, read)
                    downloadedBytes += read

                    // 更新进度（~200ms 间隔避免 UI 刷新过频）
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime > 200) {
                        val elapsed = (now - lastLogTime) / 1000f
                        val bytesDelta = downloadedBytes - lastLogBytes
                        val speed = if (elapsed > 0) (bytesDelta / elapsed) / (1024f * 1024f) else 0f

                        _downloadProgress.value = DownloadState(
                            status = DownloadState.Status.DOWNLOADING,
                            progress = downloadedBytes.toFloat() / totalBytes,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            speedMbps = speed
                        )

                        lastLogTime = now
                        lastLogBytes = downloadedBytes

                        Log.d(TAG, "下载: ${downloadedBytes / (1024 * 1024)}MB / ${totalBytes / (1024 * 1024)}MB " +
                                "(${String.format("%.1f", speed)} MB/s)")
                    }

                    // 检查取消
                    if (Thread.currentThread().isInterrupted) {
                        input.close()
                        throw CancellationException("下载被取消")
                    }

                    read = input.read(buffer)
                }
            }
        }

        val totalTime = (System.currentTimeMillis() - startTime) / 1000f
        Log.i(TAG, "下载完成: ${downloadedBytes / (1024 * 1024)}MB, 耗时 ${totalTime.toInt()}s, " +
                "平均 ${String.format("%.1f", downloadedBytes / totalTime / (1024 * 1024))} MB/s")

        return verifyAndFinalize(tempFile, totalBytes)
    }

    /**
     * 校验文件大小并重命名为正式文件名。
     */
    private fun verifyAndFinalize(file: File, expectedSize: Long): Result<File> {
        _downloadProgress.value = DownloadState(DownloadState.Status.VERIFYING,
            progress = 0.98f, downloadedBytes = file.length(), totalBytes = expectedSize)

        val actualSize = file.length()
        if (actualSize < expectedSize) {
            val msg = "文件大小校验失败: 预期 ${expectedSize / (1024 * 1024)}MB，实际 ${actualSize / (1024 * 1024)}MB"
            Log.e(TAG, msg)
            _downloadProgress.value = DownloadState(DownloadState.Status.ERROR, errorMessage = msg)
            return Result.failure(IOException(msg))
        }

        // 原子重命名
        val target = modelFile
        if (target.exists()) target.delete()
        val renamed = file.renameTo(target)
        if (!renamed) {
            // renameTo 跨文件系统可能失败，尝试 copy + delete
            Log.w(TAG, "renameTo 失败，尝试 copy 模式")
            file.copyTo(target, overwrite = true)
            file.delete()
        }

        _downloadProgress.value = DownloadState(
            DownloadState.Status.COMPLETED,
            progress = 1f,
            downloadedBytes = target.length(),
            totalBytes = target.length()
        )

        Log.i(TAG, "模型就绪: ${target.absolutePath} (${target.length() / (1024 * 1024)}MB)")
        return Result.success(target)
    }

    companion object {
        private const val TAG = "ModelDownloadManager"

        /** 默认下载地址（ModelScope 国内源） */
        private const val DEFAULT_URL =
            "https://modelscope.cn/models/Qwen/Qwen2-1.5B-Instruct-GGUF/resolve/master/" +
                    "qwen2-1.5b-instruct-q4_k_m.gguf"

        /** 备用地址（Hugging Face） */
        const val HF_URL =
            "https://huggingface.co/Qwen/Qwen2-1.5B-Instruct-GGUF/resolve/main/" +
                    "qwen2-1.5b-instruct-q4_k_m.gguf"

        /** 模型最小有效大小（bytes，防止下载了 HTML 错误页） */
        private const val MIN_MODEL_SIZE = 500L * 1024 * 1024  // 500MB

        /** 所需最低存储空间 */
        private const val REQUIRED_SPACE = 2L * 1024 * 1024 * 1024  // 2GB（留余量）

        /** 下载缓冲区 */
        private const val BUFFER_SIZE = 64 * 1024  // 64KB
    }
}
