package com.example.meetingtranscriber.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.meetingtranscriber.data.db.VocabularyEntity
import com.example.meetingtranscriber.databinding.FragmentSettingsBinding
import com.example.meetingtranscriber.network.AsrProviderType
import com.example.meetingtranscriber.network.CloudSyncManager
import com.example.meetingtranscriber.network.UpdateChecker
import com.example.meetingtranscriber.network.checkTingwuKeys
import com.example.meetingtranscriber.network.checkVolcengineKeys
import com.example.meetingtranscriber.util.StorageMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var adapter: VocabularyAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── ASR 提供商选择 ──
        setupProviderSpinner()

        adapter = VocabularyAdapter(
            onDelete = { vocab -> showDeleteConfirm(vocab) }
        )
        binding.rvVocabularies.adapter = adapter
        binding.rvVocabularies.layoutManager = LinearLayoutManager(requireContext())

        binding.btnAddVocabulary.setOnClickListener { showAddVocabularyDialog() }
        binding.btnImportVocabulary.setOnClickListener { showImportDialog() }

        // 云同步
        val syncManager = CloudSyncManager(requireContext())
        binding.btnSyncExport.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val count = syncManager.syncAll()
                if (count > 0) {
                    Toast.makeText(requireContext(), "已导出 $count 个会议", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "没有需要同步的会议", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.btnSyncImport.setOnClickListener {
            val files = syncManager.listImportFiles()
            if (files.isEmpty()) {
                Toast.makeText(requireContext(), "没有可导入的备份文件", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val names = files.map { it.nameWithoutExtension }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("导入备份")
                .setItems(names) { _, which ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val newId = syncManager.importMeeting(files[which])
                        if (newId != null) {
                            Toast.makeText(requireContext(), "导入成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), "导入失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 后台静默运行开关
        val prefs = requireContext().getSharedPreferences("meeting_prefs", android.content.Context.MODE_PRIVATE)
        binding.switchBackground.isChecked = prefs.getBoolean("background_silent", false)
        binding.switchBackground.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("background_silent", checked).apply()
        }

        // 版本信息
        val versionCode = UpdateChecker.getCurrentVersionCode(requireContext())
        binding.tvVersion.text = "版本 $versionCode"

        binding.btnCheckUpdate.setOnClickListener {
            binding.tvUpdateStatus.text = "正在检查..."
            viewLifecycleOwner.lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) {
                    UpdateChecker.check(requireContext())
                }
                if (info != null && info.versionCode > versionCode) {
                    binding.tvUpdateStatus.text = "发现新版本 ${info.versionName}"
                    AlertDialog.Builder(requireContext())
                        .setTitle("新版本 ${info.versionName}")
                        .setMessage(info.changelog.take(500))
                        .setPositiveButton("下载") { _, _ ->
                            downloadAndInstall(info)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    binding.tvUpdateStatus.text = "已是最新版本"
                }
            }
        }

        // 存储空间信息
        updateStorageInfo()
        binding.btnCleanup.setOnClickListener {
            val freed = StorageMonitor.cleanupOldRecordings(requireContext())
            Toast.makeText(requireContext(), "已释放 ${freed / (1024 * 1024)} MB", Toast.LENGTH_SHORT).show()
            updateStorageInfo()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.vocabularies.collectLatest { list ->
                adapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.errorMessage.collect { msg ->
                if (msg != null) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
            }
        }
    }

    private fun showAddVocabularyDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 20)
        }
        val nameInput = EditText(requireContext()).apply {
            hint = "词库名称"
            setSingleLine(true)
        }
        val wordsInput = EditText(requireContext()).apply {
            hint = "词汇（每行一个）"
            minLines = 4
        }
        layout.addView(nameInput)
        layout.addView(wordsInput)

        AlertDialog.Builder(requireContext())
            .setTitle("新建词库")
            .setView(layout)
            .setPositiveButton("创建") { _, _ ->
                val name = nameInput.text.toString().trim()
                val words = wordsInput.text.toString().trim().lines().filter { it.isNotBlank() }
                if (name.isNotBlank() && words.isNotEmpty()) {
                    viewModel.createVocabulary(name, words)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showImportDialog() {
        val input = EditText(requireContext()).apply {
            hint = "词库名称"
            setSingleLine(true)
        }
        val wordsInput = EditText(requireContext()).apply {
            hint = "粘贴词汇内容（每行一个词汇）"
            minLines = 6
        }

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 20)
            addView(input)
            addView(wordsInput)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("导入词库")
            .setMessage("从文本导入词汇，每行一个词")
            .setView(layout)
            .setPositiveButton("导入") { _, _ ->
                val name = input.text.toString().trim()
                val words = wordsInput.text.toString().trim().lines().filter { it.isNotBlank() }
                if (name.isNotBlank() && words.isNotEmpty()) {
                    viewModel.importFromFile(name, words)
                    Toast.makeText(requireContext(), "已导入 ${words.size} 个词汇", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteConfirm(vocab: VocabularyEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除词库")
            .setMessage("确定删除词库「${vocab.name}」？云端词库也将一并删除。")
            .setPositiveButton("删除") { _, _ -> viewModel.deleteVocabulary(vocab) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadAndInstall(info: com.example.meetingtranscriber.network.UpdateChecker.UpdateInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.tvUpdateStatus.text = "正在下载..."
            val file = withContext(Dispatchers.IO) {
                UpdateChecker.downloadApk(requireContext(), info)
            }
            if (file != null) {
                UpdateChecker.installApk(requireContext(), file)
            } else {
                Toast.makeText(requireContext(), "下载失败", Toast.LENGTH_SHORT).show()
                binding.tvUpdateStatus.text = "下载失败"
            }
        }
    }

    private fun updateStorageInfo() {
        val status = StorageMonitor.check(requireContext())
        binding.tvStorageDetail.text =
            "可用: ${status.minFreeMb}MB · 录音: ${status.recordingsSizeMb}MB"
        binding.btnCleanup.isEnabled = status.recordingsSizeMb > 0
    }

    /** 绑定 ASR 提供商 Spinner：选择 → 存 SharedPrefs → 校验密钥 → Toast */
    private fun setupProviderSpinner() {
        val spinner = binding.spinnerAsrProvider
        val types = AsrProviderType.entries.toList()
        val labels = types.map { it.displayName }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val current = AsrProviderType.fromPrefs(requireContext())
        spinner.setSelection(types.indexOf(current), false)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var isInitialSelection = true

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (isInitialSelection) {
                    isInitialSelection = false
                    return  // 跳过 setSelection 触发的初始化回调
                }
                val selected = types[pos]
                AsrProviderType.saveToPrefs(requireContext(), selected)
                val msg = when (selected) {
                    AsrProviderType.TINGWU -> checkTingwuKeys()
                    AsrProviderType.VOLCENGINE -> checkVolcengineKeys()
                }
                if (msg != null) {
                    Toast.makeText(requireContext(), "⚠️ $msg\n请在 gradle-local.properties 中配置", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "已切换至 ${selected.displayName}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class VocabularyAdapter(
    private val onDelete: (VocabularyEntity) -> Unit
) : androidx.recyclerview.widget.ListAdapter<VocabularyEntity, VocabularyAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val tvName: android.widget.TextView = view.findViewById(android.R.id.text1)
        val tvInfo: android.widget.TextView = view.findViewById(android.R.id.text2)
        val btnDelete: android.widget.TextView = view.findViewById(android.R.id.button1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // 内联简单列表项
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 16, 16, 16)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val textCol = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            (layoutParams as LinearLayout.LayoutParams).weight = 1f
            layoutParams = ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val name = android.widget.TextView(parent.context).apply {
            id = android.R.id.text1
            textSize = 16f
            setTextColor(0xFF1C1B1F.toInt())
        }
        val info = android.widget.TextView(parent.context).apply {
            id = android.R.id.text2
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        }
        textCol.addView(name)
        textCol.addView(info)
        val del = android.widget.TextView(parent.context).apply {
            id = android.R.id.button1
            text = "删除"
            setTextColor(0xFFD32F2F.toInt())
            setPadding(16, 8, 16, 8)
            textSize = 14f
        }
        layout.addView(textCol)
        layout.addView(del)
        return ViewHolder(layout)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.name
        val status = if (item.vocabularyId.isNullOrBlank()) "未上传" else "已上传云端"
        holder.tvInfo.text = "${item.wordCount} 词 · ${status} · ${item.sourceType}"
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    object DiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<VocabularyEntity>() {
        override fun areItemsTheSame(old: VocabularyEntity, new: VocabularyEntity) = old.id == new.id
        override fun areContentsTheSame(old: VocabularyEntity, new: VocabularyEntity) = old == new
    }
}
