package com.example.meetingtranscriber.ui.album

import android.Manifest
import android.app.Dialog
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.provider.MediaStore
import com.example.meetingtranscriber.databinding.FragmentAlbumBinding
import com.example.meetingtranscriber.databinding.ItemAlbumPhotoBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App 内相册页：网格展示本应用拍摄的照片（Pictures/MeetingTranscriber），点击看大图。
 *
 * 权限说明：本应用自己插入 MediaStore 的照片无需权限即可读；
 * READ_MEDIA_IMAGES 仅在重装 App 后读取旧照片时需要，拒绝也能正常用。
 */
class AlbumFragment : Fragment() {

    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: AlbumAdapter

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            loadPhotos()  // 授予与否都加载：自己拍的照片无需权限
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        adapter = AlbumAdapter(viewLifecycleOwner.lifecycleScope) { uri -> showPhoto(uri) }
        binding.rvPhotos.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvPhotos.adapter = adapter

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
                    arrayOf("Pictures/MeetingTranscriber%"),
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

    /**
     * 恢复此前拍照中断留下的 pending 照片：相机拍摄期间本进程被杀时
     * IS_PENDING 没被清零，这类照片对所有查询不可见（系统一周后自动删）。
     * 有内容的转正（重新可见），超过 1 分钟仍为空的占位行删除。
     */
    private fun recoverStuckPendingPhotos(resolver: android.content.ContentResolver) {
        if (Build.VERSION.SDK_INT < 30) return  // QUERY_ARG_MATCH_PENDING 需 API 30
        try {
            val args = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf("Pictures/MeetingTranscriber%"))
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
                        // 照片已写入，只是没转正 → 清 IS_PENDING 恢复可见
                        runCatching {
                            resolver.update(uri, android.content.ContentValues().apply {
                                put(MediaStore.Images.Media.IS_PENDING, 0)
                            }, null, null)
                        }
                    } else if (System.currentTimeMillis() / 1000 - c.getLong(dateCol) > 60) {
                        // 空占位且超 1 分钟（排除正在拍的）→ 删除
                        runCatching { resolver.delete(uri, null, null) }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AlbumFragment", "pending 照片恢复失败: ${e.message}")
        }
    }

    /** 全屏看大图（黑底，点击关闭） */
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
                // 解码原图而非 loadThumbnail：系统缩略图放大到全屏会糊；
                // 超过 2560px 按比例下采样防止整张原图占内存
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

/** 相册网格适配器：loadThumbnail 异步加载缩略图（系统自带缓存，无需图片库） */
private class AlbumAdapter(
    private val scope: CoroutineScope,
    private val onClick: (Uri) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.Holder>() {

    private val items = mutableListOf<Uri>()

    fun submit(list: List<Uri>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemAlbumPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ItemAlbumPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val uri = items[position]
        holder.job?.cancel()
        holder.binding.ivPhoto.setImageDrawable(null)
        holder.binding.ivPhoto.tag = uri
        holder.job = scope.launch {
            val resolver = holder.binding.root.context.applicationContext.contentResolver
            val bmp = withContext(Dispatchers.IO) {
                runCatching { resolver.loadThumbnail(uri, Size(512, 512), null) }.getOrNull()
            }
            // 复用校验：加载期间该格子可能已绑定到其他照片
            if (bmp != null && holder.binding.ivPhoto.tag == uri) {
                holder.binding.ivPhoto.setImageBitmap(bmp)
            }
        }
        holder.binding.root.setOnClickListener { onClick(uri) }
    }
}
