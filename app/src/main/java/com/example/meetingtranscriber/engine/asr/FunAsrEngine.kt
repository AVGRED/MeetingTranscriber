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
 * 基于 sherpa-onnx SenseVoiceSmall INT8 模型，完全本地运行。
 * 模型文件打包在 APK assets 中，首次启动自动拷贝到 filesDir/models/。
 *
 * 技术参数:
 * - 模型: SenseVoiceSmall INT8 (~227MB ONNX + 309KB tokens)
 * - sherpa-onnx: 1.12.29 (Zipformer2Ctc online model)
 * - 输入: PCM 16kHz / 16bit / mono
 * - 断句: 1 秒无语音断句，最长 20 秒强制断句，最短 0.5 秒
 * - 支持: 普通话、四川话、粤语 (zh/en/ja/ko/yue)
 */
class FunAsrEngine(private val context: Context) : AsrEngine {

    override val type: AsrEngineType = AsrEngineType.FUNASR_LOCAL

    private val initLock = Any()
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    private val _interimText = MutableStateFlow("")
    override val interimText: StateFlow<String> = _interimText

    private val _sentenceResults = MutableSharedFlow<AsrSentence>(extraBufferCapacity = 64)
    override val sentenceResults: SharedFlow<AsrSentence> = _sentenceResults

    private val _engineStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val engineStatus: StateFlow<EngineStatus> = _engineStatus

    private var totalBytesProcessed = 0L
    private var sentenceIndex = 0L

    override suspend fun initialize(context: Context): Result<Unit> {
        val current = _engineStatus.value
        if (current.state == EngineState.READY || current.state == EngineState.RUNNING) {
            return Result.success(Unit)
        }

        return withContext(Dispatchers.IO) {
            synchronized(initLock) {
                // 二次检查：可能在排队期间已被其他线程初始化
                val recheck = _engineStatus.value
                if (recheck.state == EngineState.READY || recheck.state == EngineState.RUNNING) {
                    return@withContext Result.success(Unit)
                }

                _engineStatus.value = EngineStatus(EngineState.LOADING, "正在加载语音模型...")

                try {
                    val modelFile = ensureModelFile()
                    val tokensFile = ensureTokensFile()
                    Log.i(TAG, "模型路径: ${modelFile.absolutePath} (${modelFile.length()} bytes)")

                    val config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(
                            sampleRate = SAMPLE_RATE,
                            featureDim = FEATURE_DIM
                        ),
                        modelConfig = OnlineModelConfig(
                            zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                                model = modelFile.absolutePath
                            ),
                            tokens = tokensFile.absolutePath
                        ),
                        endpointConfig = EndpointConfig(
                            rule1 = EndpointRule(
                                mustContainNonSilence = false,
                                minTrailingSilence = ENDPOINT_SILENCE_SEC,
                                minUtteranceLength = MIN_UTTERANCE_SEC
                            ),
                            rule2 = EndpointRule(
                                mustContainNonSilence = false,
                                minTrailingSilence = 10.0f,
                                minUtteranceLength = MAX_UTTERANCE_SEC
                            )
                        ),
                        enableEndpoint = true
                    )

                    recognizer = OnlineRecognizer(
                        null,  // 使用绝对路径加载，不通过 AssetManager
                        config
                    )
                    _engineStatus.value = EngineStatus(EngineState.READY, "模型已就绪")
                    Log.i(TAG, "FunASR 引擎初始化成功")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "FunASR 引擎初始化失败", e)
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "模型加载失败: ${e.message}")
                    Result.failure(e)
                }
            } // synchronized(initLock)
        }
    }

    override suspend fun start(config: AsrConfig): Result<Unit> {
        return withContext(Dispatchers.IO) {
            synchronized(initLock) {
                val rec = recognizer ?: return@withContext Result.failure(
                    IllegalStateException("引擎未初始化，请先调用 initialize()")
                )
                try {
                    // 释放旧 stream（防止重复 start 导致 native 内存泄漏）
                    stream?.let { rec.reset(it) }
                    stream = null

                    stream = rec.createStream("")
                    totalBytesProcessed = 0L
                    sentenceIndex = 0L
                    _interimText.value = ""
                    _engineStatus.value = EngineStatus(EngineState.RUNNING)
                    Log.i(TAG, "转写流已创建")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "创建转写流失败", e)
                    _engineStatus.value = EngineStatus(EngineState.ERROR, "启动失败: ${e.message}")
                    Result.failure(e)
                }
            } // synchronized(initLock)
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

            // 更新计时
            totalBytesProcessed += pcmData.size
            val elapsedMs = totalBytesProcessed / BYTES_PER_MS

            // 解码循环
            while (rec.isReady(currentStream)) {
                rec.decode(currentStream)
                val result = rec.getResult(currentStream)

                if (result.text.isNotBlank()) {
                    // 检查是否为完整句子（通过 endpoint 检测）
                    if (rec.isEndpoint(currentStream)) {
                        sentenceIndex++
                        val sentence = AsrSentence(
                            text = result.text.trim(),
                            sentenceId = sentenceIndex,
                            speakerId = "speaker_0",
                            startTimeMs = (elapsedMs - 2000L).coerceAtLeast(0),
                            endTimeMs = elapsedMs,
                            isFinal = true
                        )
                        _sentenceResults.tryEmit(sentence)
                        _interimText.value = ""
                        Log.d(TAG, "句子 #$sentenceIndex: ${sentence.text.take(50)}...")
                        // Reset stream after endpoint
                        rec.reset(currentStream)
                    } else {
                        // 中间结果
                        _interimText.value = result.text.trim()
                    }
                }
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
            currentStream.inputFinished()
            while (rec.isReady(currentStream)) {
                rec.decode(currentStream)
                val result = rec.getResult(currentStream)
                if (result.text.isNotBlank()) {
                    sentenceIndex++
                    val elapsedMs = totalBytesProcessed / BYTES_PER_MS
                    _sentenceResults.tryEmit(
                        AsrSentence(
                            text = result.text.trim(),
                            sentenceId = sentenceIndex,
                            speakerId = "speaker_0",
                            startTimeMs = (elapsedMs - 2000L).coerceAtLeast(0),
                            endTimeMs = elapsedMs,
                            isFinal = true
                        )
                    )
                }
            }
            _interimText.value = ""
            Log.i(TAG, "finalize 完成，共 $sentenceIndex 句")
        } catch (e: Exception) {
            Log.e(TAG, "finalize 异常", e)
            _engineStatus.value = EngineStatus(EngineState.ERROR, "最终处理异常: ${e.message}")
        }
    }

    override suspend fun dispose() {
        withContext(Dispatchers.IO) {
            synchronized(initLock) {
                try {
                    stream?.let { recognizer?.reset(it) }
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
                modelFile.delete()  // 清理可能的半成品
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
        private const val FEATURE_DIM = 80
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_MS = (SAMPLE_RATE * BYTES_PER_SAMPLE) / 1000

        private const val ENDPOINT_SILENCE_SEC = 1.0f
        private const val MAX_UTTERANCE_SEC = 20.0f
        private const val MIN_UTTERANCE_SEC = 0.5f

        const val MODEL_DIR = "models"
        const val ASSET_MODEL_PATH = "models"
        const val MODEL_FILE_NAME = "sense-voice-small-cn.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"
    }
}
