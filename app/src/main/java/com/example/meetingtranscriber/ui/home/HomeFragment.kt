package com.example.meetingtranscriber.ui.home

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.meetingtranscriber.MainActivity
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    companion object {
        private const val KEY_PENDING_PHOTO = "pending_photo_uri"
        private const val TAG = "HomeFragment"

        // 已知会忽略 MediaStore URI 的 OEM 厂商关键字（小写匹配）
        private val URI_IGNORING_OEMS = setOf(
            "xiaomi", "redmi", "huawei", "honor",
            "oppo", "oneplus", "realme", "vivo", "iqoo"
        )
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    /** 拍照输出的 MediaStore 占位 Uri（拍完置回 null） */
    private var pendingPhotoUri: Uri? = null

    /** FileProvider 兜底 URI（部分 OEM 需要） */
    private var pendingFileProviderUri: Uri? = null

    /** 拍照开始时间戳，用于恢复窗口 */
    private var photoTimestamp: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPhotoUri = savedInstanceState?.getString(KEY_PENDING_PHOTO)?.let(Uri::parse)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhotoUri?.let { outState.putString(KEY_PENDING_PHOTO, it.toString()) }
    }

    // ── 拍照 launcher（主通道：MediaStore URI） ──
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingPhotoUri ?: return@registerForActivityResult
            pendingPhotoUri = null
            val resolver = requireContext().contentResolver

            val size = kotlin.runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)

            android.util.Log.i(TAG, "拍照返回 success=$success uriSize=$size manufacturer=${getManufacturer()}")

            when {
                size > 0 -> {
                    // 照片成功写入我们的 URI
                    Toast.makeText(requireContext(), "已保存到相册", Toast.LENGTH_SHORT).show()
                    scheduleLateRecoveryCheck(resolver)  // 部分 OEM 延迟写入缩略图
                }
                else -> {
                    // 空文件 → 删除占位，尝试恢复
                    resolver.delete(uri, null, null)
                    android.util.Log.i(TAG, "URI 为空（OEM 可能忽略了传入的 URI），启动恢复流程")
                    // 先立即扫描一次，再设置延迟扫描
                    val found = recoverPhotoFromDefaultLocation(resolver)
                    if (!found) {
                        // 延迟再次扫描（部分相机处理需要更长时间）
                        Handler(Looper.getMainLooper()).postDelayed({
                            val foundDelayed = recoverPhotoFromDefaultLocation(resolver)
                            if (!foundDelayed && success) {
                                Toast.makeText(requireContext(), "拍照保存失败，请检查相册", Toast.LENGTH_LONG).show()
                            }
                        }, 3000L)
                    }
                }
            }
        }

    // ── 拍照 launcher（兜底通道：FileProvider URI） ──
    private val takePictureFileProviderLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val fpUri = pendingFileProviderUri ?: return@registerForActivityResult
            pendingFileProviderUri = null

            val cacheFile = File(requireContext().cacheDir, "camera/${fpUri.lastPathSegment}")
            android.util.Log.i(TAG, "FileProvider 兜底拍照返回 success=$success fileSize=${cacheFile.length()}")

            if (cacheFile.exists() && cacheFile.length() > 0) {
                // 从缓存复制到 MediaStore
                copyCacheToMediaStore(cacheFile)
                cacheFile.delete()
            } else {
                cacheFile.delete()
                // FileProvider 也失败 → 最后尝试从默认位置恢复
                val found = recoverPhotoFromDefaultLocation(requireContext().contentResolver)
                if (!found && success) {
                    Toast.makeText(requireContext(), "拍照保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvGreeting.text = viewModel.greeting
        binding.tvDate.text = viewModel.todayDate

        setupClickListeners()
        observeState()
    }

    private fun setupClickListeners() {
        binding.btnRealtime.setOnClickListener {
            (requireActivity() as MainActivity).navigateToMeeting("realtime")
        }
        binding.btnOffline.setOnClickListener {
            (requireActivity() as MainActivity).navigateToMeeting("offline")
        }
        binding.btnHistory.setOnClickListener {
            (requireActivity() as MainActivity).navigateToTab(R.id.nav_history)
        }
        binding.btnCamera.setOnClickListener { takePhoto() }
        binding.btnGallery.setOnClickListener { openGallery() }
    }

    /**
     * 拍照主入口：自适应选择最佳策略
     *
     * 策略优先级：
     * 1. Pixel/原生 Android → MediaStore URI (IS_PENDING)，兼容性最好
     * 2. 三星 → MediaStore URI (IS_PENDING)，三星相机通常尊重
     * 3. 小米/华为/OPPO/vivo → MediaStore URI (NO IS_PENDING) + FileProvider 兜底
     */
    private fun takePhoto() {
        // 检查相机可用性
        val pm = requireContext().packageManager
        if (!pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(requireContext(), "此设备没有相机", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(pm) == null) {
            Toast.makeText(requireContext(), "未找到相机应用", Toast.LENGTH_SHORT).show()
            return
        }

        photoTimestamp = System.currentTimeMillis()
        val manufacturer = getManufacturer()

        // 清理之前的相机缓存
        cleanCameraCache()

        when {
            // 策略 A：已知忽略 URI 的 OEM → 同时准备 FileProvider 兜底
            URI_IGNORING_OEMS.any { manufacturer.contains(it) } -> {
                android.util.Log.i(TAG, "检测到 ${manufacturer}，使用双通道策略")
                takePhotoWithFallback()
            }
            // 策略 B：三星 → IS_PENDING 模式
            manufacturer.contains("samsung") -> {
                android.util.Log.i(TAG, "三星设备，使用 IS_PENDING 策略")
                takePhotoWithPendingFlag()
            }
            // 策略 C：原生/Pixel → IS_PENDING 模式
            else -> {
                android.util.Log.i(TAG, "标准设备 ($manufacturer)，使用 IS_PENDING 策略")
                takePhotoWithPendingFlag()
            }
        }
    }

    /** 策略：IS_PENDING 模式（三星/Pixel/原生） */
    private fun takePhotoWithPendingFlag() {
        val resolver = requireContext().contentResolver
        var uri: Uri? = null
        try {
            val name = "MT_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MeetingTranscriber")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert 返回 null")

            pendingPhotoUri = uri
            takePictureLauncher.launch(uri)

            // 安全网：2 分钟后强制清 IS_PENDING（防止进程被杀导致照片不可见）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        val cv = ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }
                        resolver.update(uri, cv, null, null)
                    }
                    android.util.Log.i(TAG, "IS_PENDING 安全网触发")
                }, 120_000L)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "IS_PENDING 策略失败: ${e.message}，降级到兜底策略")
            pendingPhotoUri = null
            uri?.let { resolver.delete(it, null, null) }
            takePhotoWithFallback()
        }
    }

    /** 策略：双通道（MediaStore URI + FileProvider 兜底） */
    private fun takePhotoWithFallback() {
        val resolver = requireContext().contentResolver
        var uri: Uri? = null
        try {
            val name = "MT_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            // 通道 1：MediaStore URI（不带 IS_PENDING）
            uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MeetingTranscriber")
                }
            ) ?: throw IllegalStateException("MediaStore insert 返回 null")

            pendingPhotoUri = uri

            // 通道 2：准备 FileProvider 缓存文件作为兜底
            val cacheDir = File(requireContext().cacheDir, "camera")
            cacheDir.mkdirs()
            val cacheFile = File(cacheDir, name)
            pendingFileProviderUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                cacheFile
            )

            // 构建带双 URI 的 Intent（TakePicture contract 只用第一个，但 Intent extras 可携带兜底）
            // 注意：TakePicture contract 无法同时传两个 URI，这里先用 MediaStore URI
            // FileProvider URI 仅用于后续恢复流程
            takePictureLauncher.launch(uri)

        } catch (e: Exception) {
            android.util.Log.w(TAG, "拍照失败: ${e.message}")
            pendingPhotoUri = null
            pendingFileProviderUri = null
            uri?.let { resolver.delete(it, null, null) }
            Toast.makeText(requireContext(), "无法打开相机", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 从默认相机位置恢复照片。
     * 增强版：30 秒窗口 + 去重 + 多时间字段检查。
     */
    private fun recoverPhotoFromDefaultLocation(resolver: android.content.ContentResolver): Boolean {
        try {
            val thirtySecondsAgo = (photoTimestamp / 1000) - 30
            val candidates = mutableListOf<MediaStorePhoto>()

            // 扫描最近 30 秒新增/修改的照片
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED
            )

            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.DATE_ADDED} > ? OR ${MediaStore.Images.Media.DATE_MODIFIED} > ?",
                arrayOf(thirtySecondsAgo.toString(), thirtySecondsAgo.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathCol) ?: ""
                    // 跳过已在我们相册目录的照片（MediaStore URI 被接受的情况）
                    if (path.startsWith("Pictures/MeetingTranscriber")) continue

                    candidates.add(
                        MediaStorePhoto(
                            id = cursor.getLong(idCol),
                            path = path,
                            displayName = cursor.getString(nameCol) ?: "photo.jpg",
                            dateTaken = cursor.getLong(takenCol)
                        )
                    )
                }
            }

            if (candidates.isEmpty()) {
                android.util.Log.i(TAG, "恢复扫描：未找到候选照片")
                return false
            }

            android.util.Log.i(TAG, "恢复扫描：找到 ${candidates.size} 个候选照片")

            // 按拍摄时间排序（DATE_TAKEN 是毫秒时间戳），选择最接近拍照时间的
            val bestMatch = candidates.minByOrNull {
                kotlin.math.abs(it.dateTaken - photoTimestamp)
            } ?: candidates.first()

            // 去重检查：目标目录中是否已有文件名/时间相似的
            if (hasSimilarPhotoInAlbum(resolver, bestMatch)) {
                android.util.Log.i(TAG, "恢复跳过：相册中已有相似照片")
                return true  // 照片已存在，不算失败
            }

            // 复制到应用相册
            copyToAlbum(resolver, bestMatch)
            return true

        } catch (e: Exception) {
            android.util.Log.w(TAG, "恢复照片失败: ${e.message}", e)
        }
        return false
    }

    /** 检查目标目录是否已存在相似照片（防重复） */
    private fun hasSimilarPhotoInAlbum(
        resolver: android.content.ContentResolver, candidate: MediaStorePhoto
    ): Boolean {
        // 用文件名和时间窗口去重：±3 秒内、同名的照片视为重复
        val timeWindow = candidate.dateTaken - 3000
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Images.Media.DATE_TAKEN} > ?",
            arrayOf("Pictures/MeetingTranscriber%", candidate.displayName, timeWindow.toString()),
            null
        )?.use { cursor ->
            return cursor.count > 0
        }
        return false
    }

    /** 将恢复的候选照片复制到应用相册目录 */
    private fun copyToAlbum(resolver: android.content.ContentResolver, candidate: MediaStorePhoto) {
        try {
            val sourceUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, candidate.id)

            val destValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "MT_${candidate.displayName}")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MeetingTranscriber")
            }
            val destUri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, destValues)
                ?: return

            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(destUri)?.use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            android.util.Log.i(TAG, "已从 ${candidate.path} 恢复照片到相册 (ID=${candidate.id})")
            Toast.makeText(requireContext(), "已保存到相册", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "复制照片到相册失败: ${e.message}")
        }
    }

    /** 将 FileProvider 缓存文件复制到 MediaStore */
    private fun copyCacheToMediaStore(cacheFile: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = requireContext().contentResolver
                    val name = "MT_${cacheFile.name}"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MeetingTranscriber")
                    }
                    val uri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext
                    resolver.openOutputStream(uri)?.use { out ->
                        cacheFile.inputStream().use { it.copyTo(out, bufferSize = 8192) }
                    }
                    withContext(Dispatchers.Main) {
                        android.util.Log.i(TAG, "FileProvider 缓存已复制到 MediaStore")
                        Toast.makeText(requireContext(), "已保存到相册", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "缓存复制失败: ${e.message}")
            }
        }
    }

    /**
     * 延迟恢复检查：部分 OEM 相机先返回成功再异步写入
     * 注册一次性 ContentObserver，3 秒超时
     */
    private fun scheduleLateRecoveryCheck(resolver: android.content.ContentResolver) {
        // 仅对已知忽略 URI 的 OEM 开启（它们可能用默认路径写入）
        val manufacturer = getManufacturer()
        if (!URI_IGNORING_OEMS.any { manufacturer.contains(it) }) return

        android.util.Log.i(TAG, "已调度延迟恢复检查 (manufacturer=$manufacturer)")
        // 简单延迟重扫
        Handler(Looper.getMainLooper()).postDelayed({
            viewLifecycleOwner.lifecycleScope.launch {
                if (isAdded) {
                    recoverPhotoFromDefaultLocation(requireContext().contentResolver)
                }
            }
        }, 5000L)
    }

    /** 清理过期的相机缓存文件 */
    private fun cleanCameraCache() {
        val cacheDir = File(requireContext().cacheDir, "camera")
        if (!cacheDir.exists()) return
        val cutoff = System.currentTimeMillis() - 3600_000  // 1 小时
        cacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    /** 获取设备制造商（小写） */
    private fun getManufacturer(): String {
        return Build.MANUFACTURER.lowercase(Locale.ROOT)
    }

    /** 跳转 App 内相册页 */
    private fun openGallery() {
        (requireActivity() as MainActivity).navigateToAlbum()
    }

    /** 恢复扫描时使用的照片元数据 */
    private data class MediaStorePhoto(
        val id: Long,
        val path: String,
        val displayName: String,
        val dateTaken: Long
    )

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {

        // 引擎状态
        launch {
            viewModel.asrEngineName.collect { name ->
                binding.tvAsrEngine.text = name
            }
        }
        launch {
            viewModel.asrHasKey.collect { hasKey ->
                val dot = binding.dotAsrStatus.background as? GradientDrawable
                val color = if (hasKey) R.color.status_recording else R.color.status_paused
                dot?.setColor(ContextCompat.getColor(requireContext(), color))
                binding.tvAsrStatus.text = if (hasKey) "已就绪" else "未配置"
            }
        }
        launch {
            viewModel.llmEngineName.collect { name ->
                binding.tvLlmEngine.text = name
            }
        }
        launch {
            viewModel.llmHasKey.collect { hasKey ->
                val dot = binding.dotLlmStatus.background as? GradientDrawable
                val color = if (hasKey) R.color.status_recording else R.color.status_paused
                dot?.setColor(ContextCompat.getColor(requireContext(), color))
                binding.tvLlmStatus.text = if (hasKey) "已配置" else "未配置"
            }
        }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
