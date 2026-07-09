package com.example.meetingtranscriber.ui.apiconfig

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.meetingtranscriber.R
import com.example.meetingtranscriber.databinding.FragmentApiConfigBinding
import com.example.meetingtranscriber.engine.AsrEngineType
import com.example.meetingtranscriber.engine.LlmEngineType
import com.google.android.material.chip.Chip
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
        setupAsrChips()
        setupLlmChips()
        setupClickListeners()
        observeState()
        loadInitialValues()
    }

    // ═══════════════════════════════════════════════════════════
    // ASR Chip 设置
    // ═══════════════════════════════════════════════════════════

    private fun setupAsrChips() {
        AsrEngineType.entries.forEach { type ->
            // 本地引擎 (sherpa-onnx) 部分设备不兼容，暂时隐藏
            if (type == AsrEngineType.FUNASR_LOCAL) return@forEach
            val chip = Chip(requireContext()).apply {
                id = when (type) {
                    AsrEngineType.FUNASR_CLOUD -> R.id.chip_asr_funasr_cloud
                    AsrEngineType.TINGWU_CLOUD -> R.id.chip_asr_tingwu
                    AsrEngineType.VOLCENGINE_CLOUD -> R.id.chip_asr_volcengine
                    AsrEngineType.FUNASR_LOCAL -> R.id.chip_asr_funasr_local  // unreachable
                }
                text = type.displayName
                isCheckable = true
            }
            binding.chipGroupAsr.addView(chip)
        }

        binding.chipGroupAsr.setOnCheckedStateChangeListener { group, _ ->
            val checkedId = group.checkedChipId
            val engineType = when (checkedId) {
                R.id.chip_asr_funasr_cloud -> AsrEngineType.FUNASR_CLOUD
                R.id.chip_asr_tingwu -> AsrEngineType.TINGWU_CLOUD
                R.id.chip_asr_volcengine -> AsrEngineType.VOLCENGINE_CLOUD
                R.id.chip_asr_funasr_local -> AsrEngineType.FUNASR_LOCAL
                else -> return@setOnCheckedStateChangeListener
            }
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
    // LLM Chip 设置
    // ═══════════════════════════════════════════════════════════

    private fun setupLlmChips() {
        LlmEngineType.entries.forEach { type ->
            val chip = Chip(requireContext()).apply {
                id = when (type) {
                    LlmEngineType.QWEN_LOCAL -> R.id.chip_llm_qwen
                    LlmEngineType.DOUBAO_CLOUD -> R.id.chip_llm_doubao
                    LlmEngineType.DASHSCOPE_CLOUD -> R.id.chip_llm_dashscope
                }
                text = type.displayName
                isCheckable = true
            }
            binding.chipGroupLlm.addView(chip)
        }

        binding.chipGroupLlm.setOnCheckedStateChangeListener { group, _ ->
            val checkedId = group.checkedChipId
            val engineType = when (checkedId) {
                R.id.chip_llm_qwen -> LlmEngineType.QWEN_LOCAL
                R.id.chip_llm_doubao -> LlmEngineType.DOUBAO_CLOUD
                R.id.chip_llm_dashscope -> LlmEngineType.DASHSCOPE_CLOUD
                else -> return@setOnCheckedStateChangeListener
            }
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
    }

    // ═══════════════════════════════════════════════════════════
    // State observation
    // ═══════════════════════════════════════════════════════════

    private fun observeState() {
        // ASR preference
        viewModel.preferredAsrEngine.collectLatestIn(lifecycleScope) { type ->
            val chipId = when (type) {
                AsrEngineType.FUNASR_CLOUD -> R.id.chip_asr_funasr_cloud
                AsrEngineType.TINGWU_CLOUD -> R.id.chip_asr_tingwu
                AsrEngineType.VOLCENGINE_CLOUD -> R.id.chip_asr_volcengine
                AsrEngineType.FUNASR_LOCAL -> R.id.chip_asr_funasr_local
            }
            binding.chipGroupAsr.check(chipId)
            updateAsrCardVisibility(type)
        }

        // LLM preference
        viewModel.preferredLlmEngine.collectLatestIn(lifecycleScope) { type ->
            val chipId = when (type) {
                LlmEngineType.QWEN_LOCAL -> R.id.chip_llm_qwen
                LlmEngineType.DOUBAO_CLOUD -> R.id.chip_llm_doubao
                LlmEngineType.DASHSCOPE_CLOUD -> R.id.chip_llm_dashscope
            }
            binding.chipGroupLlm.check(chipId)
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
}

/** Flow collection helper — avoids boilerplate */
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectLatestIn(
    scope: androidx.lifecycle.LifecycleCoroutineScope,
    action: suspend (T) -> Unit
) {
    scope.launch { this@collectLatestIn.collectLatest { action(it) } }
}
