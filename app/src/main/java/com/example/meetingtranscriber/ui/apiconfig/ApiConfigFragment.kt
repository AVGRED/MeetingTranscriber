package com.example.meetingtranscriber.ui.apiconfig

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.meetingtranscriber.databinding.FragmentApiConfigBinding
import com.example.meetingtranscriber.engine.AsrEngineType
import com.example.meetingtranscriber.engine.LlmEngineType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class ApiConfigFragment : Fragment() {

    private var _binding: FragmentApiConfigBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ApiConfigViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentApiConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAsrDropdown()
        setupLlmDropdown()
        setupClickListeners()
        observeState()
        loadInitialValues()
    }

    // ═══════════════════════════════════════════════════════════
    // ASR 下拉菜单设置
    // ═══════════════════════════════════════════════════════════

    private fun setupAsrDropdown() {
        val items = AsrEngineType.entries.map { it.displayName }.toTypedArray()
        binding.dropdownAsr.setSimpleItems(items)
        // Material 下拉已知 bug：选中后 adapter 被过滤只剩当前项，重开前重置完整列表
        binding.dropdownAsr.setOnClickListener { binding.dropdownAsr.setSimpleItems(items) }
        binding.dropdownAsr.setOnItemClickListener { _, _, position, _ ->
            val engineType = AsrEngineType.entries[position]
            viewModel.setPreferredAsrEngine(engineType)
            updateAsrCardVisibility(engineType)
        }
    }

    private fun updateAsrCardVisibility(type: AsrEngineType) {
        binding.cardFunasrCloud.visibility = if (type == AsrEngineType.FUNASR_CLOUD) View.VISIBLE else View.GONE
        binding.cardTingwu.visibility = if (type == AsrEngineType.TINGWU_CLOUD) View.VISIBLE else View.GONE
        binding.cardVolcengine.visibility = if (type == AsrEngineType.VOLCENGINE_CLOUD) View.VISIBLE else View.GONE
    }

    // ═══════════════════════════════════════════════════════════
    // LLM 下拉菜单设置
    // ═══════════════════════════════════════════════════════════

    private fun setupLlmDropdown() {
        val items = LlmEngineType.entries.map { it.displayName }.toTypedArray()
        binding.dropdownLlm.setSimpleItems(items)
        // 同 ASR 下拉：重开前重置完整列表，避免被过滤只剩当前项
        binding.dropdownLlm.setOnClickListener { binding.dropdownLlm.setSimpleItems(items) }
        binding.dropdownLlm.setOnItemClickListener { _, _, position, _ ->
            val engineType = LlmEngineType.entries[position]
            viewModel.setPreferredLlmEngine(engineType)
            updateLlmCardVisibility(engineType)
        }
    }

    private fun updateLlmCardVisibility(type: LlmEngineType) {
        binding.cardDoubao.visibility = if (type == LlmEngineType.DOUBAO_CLOUD) View.VISIBLE else View.GONE
        binding.cardDashscope.visibility = if (type == LlmEngineType.DASHSCOPE_CLOUD) View.VISIBLE else View.GONE
    }

    // ═══════════════════════════════════════════════════════════
    // Click Listeners
    // ═══════════════════════════════════════════════════════════

    private fun setupClickListeners() {
        // FunASR Cloud URL (saved as user types — simplified, real app would use debounce)
        // 通义听悟
        binding.btnSaveTingwu.setOnClickListener {
            val akId = binding.etTingwuAkId.text?.toString() ?: ""
            val akSecret = binding.etTingwuAkSecret.text?.toString() ?: ""
            val appKey = binding.etTingwuAppKey.text?.toString() ?: ""
            viewModel.saveTingwuKeys(akId, akSecret, appKey)
            Toast.makeText(requireContext(), "通义听悟密钥已保存", Toast.LENGTH_SHORT).show()
        }
        binding.btnClearTingwu.setOnClickListener {
            viewModel.clearTingwuKeys()
            binding.etTingwuAkId.text?.clear()
            binding.etTingwuAkSecret.text?.clear()
            binding.etTingwuAppKey.text?.clear()
            Toast.makeText(requireContext(), "已清除", Toast.LENGTH_SHORT).show()
        }

        // 豆包 ASR
        binding.btnSaveVolc.setOnClickListener {
            val apiKey = binding.etVolcApiKey.text?.toString() ?: ""
            val token = binding.etVolcToken.text?.toString() ?: ""
            viewModel.saveVolcengineKeys(apiKey, token)
            Toast.makeText(requireContext(), "豆包 ASR 密钥已保存", Toast.LENGTH_SHORT).show()
        }
        binding.btnClearVolc.setOnClickListener {
            viewModel.clearVolcengineKeys()
            binding.etVolcApiKey.text?.clear()
            binding.etVolcToken.text?.clear()
            Toast.makeText(requireContext(), "已清除", Toast.LENGTH_SHORT).show()
        }

        // 豆包 LLM (ARK)
        binding.btnSaveArk.setOnClickListener {
            val apiKey = binding.etArkApiKey.text?.toString() ?: ""
            val endpointId = binding.etArkEndpointId.text?.toString() ?: ""
            viewModel.saveArkKey(apiKey, endpointId)
            Toast.makeText(requireContext(), "火山方舟密钥已保存", Toast.LENGTH_SHORT).show()
        }
        binding.btnClearArk.setOnClickListener {
            viewModel.clearArkKey()
            binding.etArkApiKey.text?.clear()
            binding.etArkEndpointId.text?.clear()
            Toast.makeText(requireContext(), "已清除", Toast.LENGTH_SHORT).show()
        }

        // DashScope
        binding.btnSaveDashscope.setOnClickListener {
            val apiKey = binding.etDashscopeApiKey.text?.toString() ?: ""
            viewModel.saveDashScopeKey(apiKey)
            Toast.makeText(requireContext(), "DashScope 密钥已保存", Toast.LENGTH_SHORT).show()
        }
        binding.btnClearDashscope.setOnClickListener {
            viewModel.clearDashScopeKey()
            binding.etDashscopeApiKey.text?.clear()
            Toast.makeText(requireContext(), "已清除", Toast.LENGTH_SHORT).show()
        }

        // Auto fallback toggle
        binding.switchAutoFallback.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoFallback(isChecked)
        }

        // API 接入说明
        binding.btnApiGuide.setOnClickListener { showApiGuide() }
    }

    // ═══════════════════════════════════════════════════════════
    // State observation
    // ═══════════════════════════════════════════════════════════

    private fun observeState() {
        // ASR preference
        viewModel.preferredAsrEngine.collectLatestIn(lifecycleScope) { type ->
            if (binding.dropdownAsr.text?.toString() != type.displayName) {
                binding.dropdownAsr.setText(type.displayName, false)
            }
            updateAsrCardVisibility(type)
        }

        // LLM preference
        viewModel.preferredLlmEngine.collectLatestIn(lifecycleScope) { type ->
            if (binding.dropdownLlm.text?.toString() != type.displayName) {
                binding.dropdownLlm.setText(type.displayName, false)
            }
            updateLlmCardVisibility(type)
        }

        viewModel.autoFallback.collectLatestIn(lifecycleScope) { enabled ->
            binding.switchAutoFallback.isChecked = enabled
        }

        viewModel.funasrCloudUrl.collectLatestIn(lifecycleScope) { url ->
            if (binding.etFunasrUrl.text?.toString() != url) {
                binding.etFunasrUrl.setText(url)
            }
        }
    }

    private fun loadInitialValues() {
        binding.etTingwuAkId.setText(viewModel.tingwuAkId.value)
        binding.etTingwuAkSecret.setText(viewModel.tingwuAkSecret.value)
        binding.etTingwuAppKey.setText(viewModel.tingwuAppKey.value)
        binding.etVolcApiKey.setText(viewModel.volcAsrApiKey.value)
        binding.etVolcToken.setText(viewModel.volcAsrToken.value)
        binding.etArkApiKey.setText(viewModel.arkApiKey.value)
        binding.etArkEndpointId.setText(viewModel.arkEndpointId.value)
        binding.etDashscopeApiKey.setText(viewModel.dashScopeApiKey.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ═══════════════════════════════════════════════════════════
    // API 接入说明
    // ═══════════════════════════════════════════════════════════

    private fun showApiGuide() {
        val message = """
            【FunASR 云端】— WebSocket 地址

            自部署 FunASR 服务端后获得，例如：
            ws://your-server:10095
            或 wss://your-domain.com/ws

            部署指南：https://github.com/modelscope/FunASR

            ━━━━━━━━━━━━━━━━━━━━━━

            【通义听悟】— 阿里云实时语音识别

            ① 登录阿里云控制台：
            https://ram.console.aliyun.com/manage/ak
            创建 AccessKey → 获取 AccessKey ID + Secret

            ② 开通智能语音交互服务：
            https://nls-portal.console.aliyun.com
            创建项目 → 获取 App Key

            ━━━━━━━━━━━━━━━━━━━━━━

            【豆包 ASR】— 火山引擎语音识别

            ① 登录火山引擎控制台：
            https://console.volcengine.com/iam/keymanage
            创建 Access Key → 获取 API Key

            ② 开通语音识别服务获取 Access Token：
            https://console.volcengine.com/speech/service/0

            ━━━━━━━━━━━━━━━━━━━━━━

            【豆包 LLM】— 火山方舟大模型

            ① 登录火山方舟控制台：
            https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey
            创建 API Key

            ② 创建推理端点（Endpoint）：
            https://console.volcengine.com/ark/region:ark+cn-beijing/endpoint
            选择模型 → 创建端点 → 获取端点 ID

            ━━━━━━━━━━━━━━━━━━━━━━

            【DashScope】— 阿里云灵积（通义千问）

            ① 登录 DashScope 控制台：
            https://dashscope.console.aliyun.com/apiKey
            创建 API Key

            ② 注意：RAM AccessKey 不适用于 DashScope，
            必须使用 DashScope 专属 API Key。
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("📖 API 接入说明")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }
}

/** Flow collection helper — avoids boilerplate */
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectLatestIn(
    scope: androidx.lifecycle.LifecycleCoroutineScope,
    action: suspend (T) -> Unit
) {
    scope.launch { this@collectLatestIn.collectLatest { action(it) } }
}
