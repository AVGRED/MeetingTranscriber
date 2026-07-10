package com.example.meetingtranscriber.engine.asr

import android.content.Context
import android.util.Log
import com.example.meetingtranscriber.engine.*
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FunASR 离线语音转写引擎。
 *
 * 基于 sherpa-onnx SenseVoiceSmall 模型，完全本地运行。
 * SenseVoice 只支持 OfflineRecognizer（非流式），因此使用
 * 伪流式：累积音频 → 定时 decode → 逐次返回中间结果。
 *
 * 技术参数:
 * - 模型: SenseVoiceSmall (~227MB ONNX + 309KB tokens)
 * - sherpa-onnx: 1.12.29
 * - 输入: PCM 16kHz / 16bit / mono
 * - 支持: 普通话、粤语、英语、中英混合 (auto/zh/en/yue)
 */
class FunAsrEngine(private val context: Context) : AsrEngine {

    override val type: AsrEngineType = AsrEngineType.FUNASR_LOCAL

    private val initLock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var stream: OfflineStream? = null

    private val _interimText = MutableStateFlow("")
    override val interimText: StateFlow<String> = _interimText

    private val _sentenceResults = MutableSharedFlow<AsrSentence>(extraBufferCapacity = 64)
    override val sentenceResults: SharedFlow<AsrSentence> = _sentenceResults

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus

    private var totalBytesProcessed = 0L
    private var sentenceIndex = 0L
    private var lastDecodedBytes = 0L
    @Volatile private var currentSpeakerIndex = 0
    /** 每累积 N 字节音频做一次 decode（~0.5 秒） */
    private val decodeIntervalBytes = SAMPLE_RATE * BYTES_PER_SAMPLE / 2

    override suspend fun initialize(context: Context): Result<Unit> {
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return Result.success(Unit)
        }

        return withContext(Dispatchers.IO) {
            synchronized(initLock) {
                val recheck = _engineStatus.value
                if (recheck.state == EngineState.READY || recheck.state == EngineState.RUNNING) {
                    return@withContext Result.success(Unit)
                }

                _engineStatus.value = EngineStatus(EngineState.LOADING, "正在加载语音模型...")

                try {
                    val modelFile = ensureModelFile()
                    val tokensFile = ensureTokensFile()
                    Log.i(TAG, "模型路径: ${modelFile.absolutePath} (${modelFile.length()} bytes)")

                    val senseVoiceConfig = OfflineSenseVoiceModelConfig(
                        model = modelFile.absolutePath,
                        language = "auto"
                    )

                    val modelConfig = OfflineModelConfig(
                        senseVoice = senseVoiceConfig,
                        tokens = tokensFile.absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        debug = false
                    )

                    val config = OfflineRecognizerConfig(modelConfig = modelConfig)

                    recognizer = OfflineRecognizer(
                        null,  // 使用绝对路径加载，不通过 AssetManager
                        config
                    )
                    _engineStatus.value = EngineStatus(EngineState.READY, "模型已就绪")
                    Log.i(TAG, "FunASR 引擎初始化成功 (SenseVoice OfflineRecognizer)")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "FunASR 引擎初始化失败", e)
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "模型加载失败: ${e.message}")
                    Result.failure(e)
                }
            }
        }
    }

    override suspend fun start(config: AsrConfig): Result<Unit> {
        return withContext(Dispatchers.IO) {
            synchronized(initLock) {
                val rec = recognizer ?: return@withContext Result.failure(
                    IllegalStateException("引擎未初始化，请先调用 initialize()")
                )
                try {
                    stream = rec.createStream()
                    totalBytesProcessed = 0L
                    lastDecodedBytes = 0L
                    sentenceIndex = 0L
                    currentSpeakerIndex = 0
                    _interimText.value = ""
                    _engineStatus.value = EngineStatus(EngineState.RUNNING)
                    Log.i(TAG, "转写流已创建")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "创建转写流失败", e)
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "启动失败: ${e.message}")
                    Result.failure(e)
                }
            }
        }
    }

    override fun processAudio(pcmData: ByteArray) {
        val rec = recognizer ?: return
        val currentStream = stream ?: return

        if (_engineStatus.value.state != EngineState.RUNNING) return

        try {
            // PCM 16bit → FloatArray
            val sampleCount = pcmData.size / BYTES_PER_SAMPLE
            val samples = FloatArray(sampleCount)
            ByteBuffer.wrap(pcmData)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .let { buf ->
                    buf.rewind()
                    for (i in 0 until sampleCount) {
                        samples[i] = buf[i].toFloat() / 32768f
                    }
                }

            currentStream.acceptWaveform(samples, SAMPLE_RATE)
            totalBytesProcessed += pcmData.size

            // 每 ~0.5 秒音频做一次 decode（伪流式）
            if (totalBytesProcessed - lastDecodedBytes >= decodeIntervalBytes) {
                rec.decode(currentStream)
                val result = rec.getResult(currentStream)

                if (result.text.isNotBlank()) {
                    _interimText.value = result.text.trim()
                }
                lastDecodedBytes = totalBytesProcessed
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理音频帧异常", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "音频处理异常: ${e.message}")
        }
    }

    override fun finalize() {
        val rec = recognizer ?: return
        val currentStream = stream ?: return

        try {
            // 最后一次 decode，获取完整结果
            rec.decode(currentStream)
            val result = rec.getResult(currentStream)

            if (result.text.isNotBlank()) {
                sentenceIndex++
                val elapsedMs = totalBytesProcessed / BYTES_PER_MS
                _sentenceResults.tryEmit(
                    AsrSentence(
                        text = result.text.trim(),
                        sentenceId = sentenceIndex,
                        speakerId = "speaker_$currentSpeakerIndex",
                        startTimeMs = maxOf(0, elapsedMs - 5000),
                        endTimeMs = elapsedMs,
                        isFinal = true
                    )
                )
            }
            _interimText.value = ""
            Log.i(TAG, "finalize 完成，共 $sentenceIndex 句")
        } catch (e: Exception) {
            Log.e(TAG, "finalize 异常", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "最终处理异常: ${e.message}")
        }
    }

    /**
     * 切换说话人 — 结束当前语音段，为下一个说话人创建新流。
     * 基于 VAD 检测到的长时间沉默触发（通常 2 秒以上）。
     */
    fun startNewSpeakerTurn() {
        // 先结束当前流
        finalize()
        // 创建新流
        val rec = recognizer ?: return
        stream = rec.createStream()
        currentSpeakerIndex++
        lastDecodedBytes = totalBytesProcessed
        _interimText.value = ""
        Log.i(TAG, "切换到说话人 speaker_$currentSpeakerIndex")
    }

    override suspend fun dispose() {
        withContext(Dispatchers.IO) {
            synchronized(initLock) {
                try {
                    recognizer?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "dispose 异常", e)
                }
                stream = null
                recognizer = null
                _interimText.value = ""
                _engineStatus.value = EngineStatus(EngineState.IDLE)
                Log.i(TAG, "FunASR 引擎已释放")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 模型文件管理
    // ═══════════════════════════════════════════════════════════

    private fun ensureModelFile(): File {
        val modelDir = File(context.filesDir, MODEL_DIR)
        modelDir.mkdirs()
        val modelFile = File(modelDir, MODEL_FILE_NAME)

        if (!modelFile.exists()) {
            Log.i(TAG, "首次启动，拷贝模型从 assets...")
            val startTime = System.currentTimeMillis()
            val tmpFile = File(modelDir, "$MODEL_FILE_NAME.tmp")
            try {
                context.assets.open("$ASSET_MODEL_PATH/$MODEL_FILE_NAME").use { input ->
                    tmpFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmpFile.renameTo(modelFile)) {
                    tmpFile.copyTo(modelFile, overwrite = true)
                    tmpFile.delete()
                }
            } catch (e: Exception) {
                tmpFile.delete()
                modelFile.delete()
                throw e
            }
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            Log.i(TAG, "模型拷贝完成，耗时 ${elapsed}s，大小 ${modelFile.length()} bytes")
        }

        return modelFile
    }

    private fun ensureTokensFile(): File {
        val modelDir = File(context.filesDir, MODEL_DIR)
        modelDir.mkdirs()
        val tokensFile = File(modelDir, TOKENS_FILE_NAME)
        if (!tokensFile.exists()) {
            context.assets.open("$ASSET_MODEL_PATH/$TOKENS_FILE_NAME").use { input ->
                tokensFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return tokensFile
    }

    companion object {
        private const val TAG = "FunAsrEngine"

        const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_MS = (SAMPLE_RATE * BYTES_PER_SAMPLE) / 1000

        const val MODEL_DIR = "models"
        const val ASSET_MODEL_PATH = "models"
        const val MODEL_FILE_NAME = "sense-voice-small-cn.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"
    }
}
