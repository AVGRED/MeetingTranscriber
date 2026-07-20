package com.example.meetingtranscriber.ui.album

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.meetingtranscriber.databinding.FragmentAlbumBinding
import com.example.meetingtranscriber.databinding.ItemAlbumPhotoBinding
import com.example.meetingtranscriber.ui.home.Camera2CaptureHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumFragment : Fragment() {

    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: AlbumAdapter

    // ── 多选状态 ──
    private val selectedUris = mutableSetOf<Uri>()
    private var isSelectionMode = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            loadPhotos()
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── 普通模式顶栏 ──
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        // ── 选择模式顶栏 ──
        binding.btnCancelSelect.setOnClickListener { exitSelectionMode() }
        binding.btnDelete.setOnClickListener { confirmDeleteSelected() }
        binding.btnShare.setOnClickListener { shareSelected() }
        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }

        // ── 5 列网格 ──
        adapter = AlbumAdapter(viewLifecycleOwner.lifecycleScope) { uri ->
            if (isSelectionMode) {
                toggleSelection(uri)
            } else {
                showPhoto(uri)
            }
        }
        adapter.onLongClick = { uri ->
            if (!isSelectionMode) {
                showPhotoContextMenu(uri)
            }
        }
        binding.rvPhotos.layoutManager = GridLayoutManager(requireContext(), 5)
        binding.rvPhotos.adapter = adapter

        checkPermissionAndLoad()
    }

    // ═══════════════════════════════════════════════
    // 权限 & 加载
    // ═══════════════════════════════════════════════

    private fun checkPermissionAndLoad() {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED) {
            loadPhotos()
        } else {
            permissionLauncher.launch(perm)
        }
    }

    private fun loadPhotos() {
        val resolver = requireContext().applicationContext.contentResolver
        viewLifecycleOwner.lifecycleScope.launch {
            val uris = withContext(Dispatchers.IO) {
                recoverStuckPendingPhotos(resolver)
                val list = mutableListOf<Uri>()
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf("${Camera2CaptureHelper.ALBUM_PATH}%"),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        list.add(
                            ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                cursor.getLong(idCol)
                            )
                        )
                    }
                }
                list
            }
            if (_binding == null) return@launch
            adapter.submit(uris)
            binding.tvEmpty.visibility = if (uris.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ═══════════════════════════════════════════════
    // Pending 照片恢复
    // ═══════════════════════════════════════════════

    private fun recoverStuckPendingPhotos(resolver: android.content.ContentResolver) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val args = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf("${Camera2CaptureHelper.ALBUM_PATH}%"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.IS_PENDING,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED
                ),
                args, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val pendingCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    if (c.getInt(pendingCol) != 1) continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
                    if (c.getLong(sizeCol) > 0) {
                        runCatching {
                            resolver.update(uri, android.content.ContentValues().apply {
                                put(MediaStore.Images.Media.IS_PENDING, 0)
                            }, null, null)
                        }
                    } else if (System.currentTimeMillis() / 1000 - c.getLong(dateCol) > 60) {
                        runCatching { resolver.delete(uri, null, null) }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AlbumFragment", "pending 照片恢复失败: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════
    // 多选逻辑
    // ═══════════════════════════════════════════════

    private fun enterSelectionMode(firstUri: Uri) {
        isSelectionMode = true
        selectedUris.clear()
        selectedUris.add(firstUri)
        updateSelectionUI()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedUris.clear()
        updateSelectionUI()
    }

    private fun toggleSelection(uri: Uri) {
        if (!isSelectionMode) {
            enterSelectionMode(uri)
        } else {
            if (selectedUris.contains(uri)) {
                selectedUris.remove(uri)
                if (selectedUris.isEmpty()) {
                    exitSelectionMode()
                    return
                }
            } else {
                selectedUris.add(uri)
            }
            updateSelectionUI()
        }
    }

    private fun updateSelectionUI() {
        if (isSelectionMode) {
            binding.layoutNormalBar.visibility = View.GONE
            binding.layoutSelectionBar.visibility = View.VISIBLE
            binding.tvSelectCount.text = "已选 ${selectedUris.size} 张"
            binding.btnShare.isEnabled = selectedUris.isNotEmpty()
            binding.btnDelete.isEnabled = selectedUris.isNotEmpty()
        } else {
            binding.layoutNormalBar.visibility = View.VISIBLE
            binding.layoutSelectionBar.visibility = View.GONE
        }
        // 刷新 Adapter 显示选中状态
        adapter.setSelectionState(isSelectionMode, selectedUris.toSet())
        updateSelectAllButton()
    }

    private fun toggleSelectAll() {
        val all = adapter.currentList
        if (selectedUris.size == all.size) {
            // 已全选 → 取消全选
            selectedUris.clear()
        } else {
            // 未全选 → 全选
            selectedUris.addAll(all)
        }
        updateSelectionUI()
    }

    private fun updateSelectAllButton() {
        val all = adapter.currentList
        binding.btnSelectAll.text =
            if (all.isNotEmpty() && selectedUris.size == all.size) "取消全选" else "全选"
    }

    // ═══════════════════════════════════════════════
    // 删除 & 分享
    // ═══════════════════════════════════════════════

    private fun confirmDeleteSelected() {
        if (selectedUris.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("删除照片")
            .setMessage("确定要删除选中的 ${selectedUris.size} 张照片吗？\n此操作不可恢复。")
            .setPositiveButton("删除") { _, _ -> deleteSelected() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelected() {
        val uris = selectedUris.toList()
        viewLifecycleOwner.lifecycleScope.launch {
            var deleted = 0
            withContext(Dispatchers.IO) {
                val resolver = requireContext().applicationContext.contentResolver
                for (uri in uris) {
                    if (resolver.delete(uri, null, null) > 0) deleted++
                }
            }
            if (deleted > 0) {
                Toast.makeText(requireContext(), "已删除 $deleted 张", Toast.LENGTH_SHORT).show()
            }
            exitSelectionMode()
            loadPhotos()
        }
    }

    private fun shareSelected() {
        val uris = selectedUris.toList()
        if (uris.isEmpty()) return

        if (uris.size == 1) {
            // 单张：直接分享 MediaStore URI
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享照片"))
        } else {
            // 多张：先复制到缓存目录，通过 FileProvider 分享
            viewLifecycleOwner.lifecycleScope.launch {
                val files = withContext(Dispatchers.IO) {
                    val cacheDir = requireContext().cacheDir.resolve("share_tmp")
                    cacheDir.mkdirs()
                    cacheDir.listFiles()?.forEach { it.delete() }
                    val resolver = requireContext().applicationContext.contentResolver
                    uris.mapIndexedNotNull { idx, uri ->
                        try {
                            val file = cacheDir.resolve("photo_$idx.jpg")
                            resolver.openInputStream(uri)?.use { input ->
                                file.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            file
                        } catch (_: Exception) { null }
                    }
                }
                if (files.isEmpty()) {
                    Toast.makeText(requireContext(), "分享失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val shareUris = files.map { file ->
                    FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        file
                    )
                }
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/jpeg"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(shareUris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "分享 ${files.size} 张照片"))
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 大图查看 & 上下文菜单
    // ═══════════════════════════════════════════════

    /** 长按照片弹出菜单：看大图 / 分享 / 删除 / 多选 */
    private fun showPhotoContextMenu(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf("查看大图", "分享", "删除", "多选")) { _, which ->
                when (which) {
                    0 -> showPhoto(uri)
                    1 -> shareSinglePhoto(uri)
                    2 -> confirmDeleteSingle(uri)
                    3 -> enterSelectionMode(uri)
                }
            }
            .show()
    }

    private fun shareSinglePhoto(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享照片"))
    }

    private fun confirmDeleteSingle(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除照片")
            .setMessage("确定要删除这张照片吗？")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        requireContext().applicationContext.contentResolver.delete(uri, null, null)
                    }
                    loadPhotos()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 全屏看大图 */
    private fun showPhoto(uri: Uri) {
        val imageView = ImageView(requireContext()).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(imageView)
        imageView.setOnClickListener { dialog.dismiss() }

        val resolver = requireContext().applicationContext.contentResolver
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    android.graphics.ImageDecoder.decodeBitmap(
                        android.graphics.ImageDecoder.createSource(resolver, uri)
                    ) { decoder, info, _ ->
                        val maxDim = maxOf(info.size.width, info.size.height)
                        if (maxDim > 2560) {
                            val scale = 2560f / maxDim
                            decoder.setTargetSize(
                                (info.size.width * scale).toInt(),
                                (info.size.height * scale).toInt()
                            )
                        }
                    }
                }.getOrNull()
            }
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
            } else {
                Toast.makeText(context ?: return@launch, "照片加载失败", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ═══════════════════════════════════════════════════════════════
// 相册网格适配器
// ═══════════════════════════════════════════════════════════════

private class AlbumAdapter(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onClick: (Uri) -> Unit
) : androidx.recyclerview.widget.ListAdapter<Uri, AlbumAdapter.Holder>(DIFF) {

    companion object {
        private val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<Uri>() {
            override fun areItemsTheSame(oldItem: Uri, newItem: Uri) = oldItem == newItem
            override fun areContentsTheSame(oldItem: Uri, newItem: Uri) = oldItem == newItem
        }

        private val thumbCache = object : android.util.LruCache<Uri, android.graphics.Bitmap>(
            (Runtime.getRuntime().maxMemory() / 8).toInt()
        ) {
            override fun sizeOf(key: Uri, value: android.graphics.Bitmap) = value.byteCount
        }
    }

    private var isSelectionMode = false
    private var selectedUris = emptySet<Uri>()
    var onLongClick: ((Uri) -> Unit)? = null

    fun submit(list: List<Uri>) = submitList(list)

    fun setSelectionState(mode: Boolean, selected: Set<Uri>) {
        isSelectionMode = mode
        selectedUris = selected
        // 触发 Adapter 全量重绑以刷新勾选/遮罩
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemAlbumPhotoBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {
        var job: kotlinx.coroutines.Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAlbumPhotoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val uri = getItem(position)
        holder.job?.cancel()
        holder.binding.ivPhoto.tag = uri

        // ── 选中状态 ──
        val isSelected = selectedUris.contains(uri)
        holder.binding.vSelectOverlay.visibility =
            if (isSelected) View.VISIBLE else View.GONE
        holder.binding.ivCheck.visibility =
            if (isSelected) View.VISIBLE else View.GONE

        // ── 缩略图加载 ──
        val cached = thumbCache.get(uri)
        if (cached != null) {
            holder.binding.ivPhoto.setImageBitmap(cached)
        } else {
            holder.binding.ivPhoto.setImageDrawable(null)
            holder.job = scope.launch {
                val resolver = holder.binding.root.context.applicationContext.contentResolver
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        resolver.loadThumbnail(uri, android.util.Size(512, 512), null)
                    }.getOrNull()
                }
                if (bmp != null) {
                    thumbCache.put(uri, bmp)
                    if (holder.binding.ivPhoto.tag == uri) {
                        holder.binding.ivPhoto.setImageBitmap(bmp)
                    }
                }
            }
        }

        // ── 点击 / 长按 ──
        holder.binding.root.setOnClickListener { onClick(uri) }
        holder.binding.root.setOnLongClickListener {
            onLongClick?.invoke(uri)
            true
        }
    }
}
