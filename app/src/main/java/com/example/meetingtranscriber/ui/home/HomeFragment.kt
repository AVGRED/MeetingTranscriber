package com.example.meetingtranscriber.ui.home

import android.content.ContentValues
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.meetingtranscriber.MainActivity
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.data.model.MeetingInfo
import com.example.meetingtranscriber.databinding.FragmentHomeBinding
import com.example.meetingtranscriber.engine.llm.ModelDownloadManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    companion object {
        private const val KEY_PENDING_PHOTO = "pending_photo_uri"
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    /** 拍照输出的 MediaStore 占位 Uri（拍完置回 null） */
    private var pendingPhotoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 相机在前台时本进程可能被杀（低端机常见）：恢复占位 Uri，
        // 否则拍照结果回来时无处清 IS_PENDING，照片永远不可见
        pendingPhotoUri = savedInstanceState?.getString(KEY_PENDING_PHOTO)?.let(Uri::parse)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhotoUri?.let { outState.putString(KEY_PENDING_PHOTO, it.toString()) }
    }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingPhotoUri ?: return@registerForActivityResult
            pendingPhotoUri = null
            val resolver = requireContext().contentResolver
            // 不信任 success 标志（部分国产相机写入成功却报取消）：以照片实际有无内容为准
            val size = kotlin.runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)
            android.util.Log.i("HomeFragment", "拍照返回 success=$success size=$size")
            if (size > 0) {
                Toast.makeText(requireContext(), "已保存到相册", Toast.LENGTH_SHORT).show()
            } else {
                resolver.delete(uri, null, null)  // 取消或写入失败 → 清掉空记录
                if (success) {
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
        binding.btnViewAll.setOnClickListener {
            (requireActivity() as MainActivity).navigateToTab(R.id.nav_history)
        }
        binding.btnDownloadModel.setOnClickListener {
            viewModel.downloadQwenModel()
        }
        binding.btnCamera.setOnClickListener { takePhoto() }
        binding.btnGallery.setOnClickListener { openGallery() }
    }

    /** 系统相机拍照，直接写入公共相册 Pictures/MeetingTranscriber（API 29+ 免权限）。
     *  不用 IS_PENDING 占位（部分国产相机写不进 pending Uri，导致照片丢失），
     *  取消/失败的空记录由拍照回调按实际大小清理。 */
    private fun takePhoto() {
        val resolver = requireContext().contentResolver
        var uri: Uri? = null
        try {
            val name = "MT_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MeetingTranscriber")
                }
            ) ?: throw IllegalStateException("MediaStore insert 返回 null")
            pendingPhotoUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            android.util.Log.w("HomeFragment", "拉起相机失败: ${e.message}")
            pendingPhotoUri = null
            uri?.let { resolver.delete(it, null, null) }
            Toast.makeText(requireContext(), "无法打开相机", Toast.LENGTH_SHORT).show()
        }
    }

    /** 跳转 App 内相册页 */
    private fun openGallery() {
        (requireActivity() as MainActivity).navigateToAlbum()
    }

    private fun observeState() {
        // repeatOnLifecycle(STARTED)：Tab 被 setMaxLifecycle(CREATED) 降级隐藏时
        // 全部收集器自动停跑，切回时自动恢复并重放最新 StateFlow 值
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

        // 引擎状态
        launch {
            viewModel.asrEngineName.collect { name ->
                binding.tvAsrEngine.text = name
            }
        }
        launch {
            viewModel.asrHasKey.collect { hasKey ->
                val dot = binding.dotAsrStatus.background as? GradientDrawable
                dot?.setColor(
                    ContextCompat.getColor(requireContext(),
                        if (hasKey) R.color.status_recording else R.color.status_paused
                    )
                )
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
                dot?.setColor(
                    ContextCompat.getColor(requireContext(),
                        if (hasKey) R.color.status_recording else R.color.status_paused
                    )
                )
            }
        }

        // 下载按钮显示/隐藏
        launch {
            viewModel.isModelDownloadNeeded.collect { needed ->
                binding.btnDownloadModel.visibility =
                    if (needed) View.VISIBLE else View.GONE
            }
        }

        // 模型下载进度
        launch {
            viewModel.modelDownloadProgress.collectLatest { state ->
                when (state.status) {
                    ModelDownloadManager.DownloadState.Status.IDLE,
                    ModelDownloadManager.DownloadState.Status.COMPLETED -> {
                        binding.progressModelDownload.visibility = View.GONE
                        binding.tvDownloadStatus.visibility = View.GONE
                    }
                    ModelDownloadManager.DownloadState.Status.DOWNLOADING -> {
                        binding.progressModelDownload.visibility = View.VISIBLE
                        binding.progressModelDownload.progress = state.percent
                        binding.tvDownloadStatus.visibility = View.VISIBLE
                        binding.tvDownloadStatus.text =
                            "Qwen 模型下载中 ${state.percent}% (${state.downloadedMB.toInt()}MB / ${state.totalMB.toInt()}MB)"
                    }
                    ModelDownloadManager.DownloadState.Status.VERIFYING -> {
                        binding.progressModelDownload.visibility = View.VISIBLE
                        binding.progressModelDownload.isIndeterminate = true
                        binding.tvDownloadStatus.visibility = View.VISIBLE
                        binding.tvDownloadStatus.text = "正在校验模型文件..."
                    }
                    ModelDownloadManager.DownloadState.Status.ERROR -> {
                        binding.progressModelDownload.visibility = View.GONE
                        binding.tvDownloadStatus.visibility = View.VISIBLE
                        binding.tvDownloadStatus.text = "下载失败: ${state.errorMessage ?: "未知错误"}"
                    }
                    else -> {}
                }
            }
        }

        // 统计数字
        launch {
            viewModel.meetingCount.collect { count ->
                binding.tvMeetingCount.text = count.toString()
            }
        }
        launch {
            viewModel.recordingCount.collect { count ->
                binding.tvRecordingCount.text = count.toString()
            }
        }
        launch {
            viewModel.recordingLabel.collect { label ->
                binding.tvRecordingLabel.text = label
            }
        }
        launch {
            viewModel.freeStorageGB.collect { gb ->
                binding.tvFreeStorage.text = gb
            }
        }

        // 最近会议
        launch {
            viewModel.recentMeetings.collect { meetings ->
                renderRecentMeetings(meetings)
            }
        }
            }
        }
    }

    private fun renderRecentMeetings(meetings: List<MeetingInfo>) {
        val container = binding.layoutRecentMeetings
        container.removeAllViews()

        if (meetings.isEmpty()) {
            binding.tvEmptyRecent.visibility = View.VISIBLE
            return
        }
        binding.tvEmptyRecent.visibility = View.GONE

        for (meeting in meetings) {
            val card = layoutInflater.inflate(R.layout.item_meeting_home, container, false)
            card.findViewById<TextView>(R.id.tv_meeting_title).text = meeting.title
            card.findViewById<TextView>(R.id.tv_meeting_info).text =
                "${meeting.date} · ${meeting.durationSeconds / 60}分钟 · ${meeting.speakerCount}人"
            card.setOnClickListener {
                // 跳转历史 tab 并打开详情（通过 MainActivity）
                (requireActivity() as MainActivity).navigateToMeetingDetail(meeting.id)
            }
            container.addView(card)
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
