package com.example.meetingtranscriber.ui.detail

import android.media.MediaPlayer
import android.util.Log
import android.view.View
import android.widget.SeekBar
import com.example.meetingtranscriber.audio.AudioCacheManager
import com.example.meetingtranscriber.databinding.FragmentDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 详情页录音播放器（MediaPlayer + SeekBar 封装）
 *
 * 加密 MTEW 录音经 AudioCacheManager 转标准 WAV 后播放。
 * 现在用 [AudioPlayerController] 控制 [FragmentDetailBinding.layoutAudioSection] 的可见性，
 * 录音存在/缺失/无录音三种状态有明确的视觉反馈。
 */
class AudioPlayerController(
    private val binding: FragmentDetailBinding,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "AudioPlayerController"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var prepareJob: Job? = null
    private var boundPath: String? = null

    init {
        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.seekAudio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateTimeText(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { mediaPlayer?.seekTo(it.progress) }
            }
        })
    }

    /** meeting 响应式 Flow 每次 emit 都会调用；path 未变则跳过，避免重置播放进度 */
    fun bind(audioFilePath: String?) {
        if (audioFilePath == boundPath) return
        boundPath = audioFilePath
        resetPlayer()

        when {
            audioFilePath == null -> {
                // 老会议本无录音 → 显示无录音提示
                binding.layoutAudioSection.visibility = View.GONE
                binding.layoutPlayer.visibility = View.GONE
                binding.tvAudioMissing.visibility = View.VISIBLE
            }
            !File(audioFilePath).exists() -> {
                // 文件被清理 → 显示缺失提示
                binding.layoutAudioSection.visibility = View.GONE
                binding.layoutPlayer.visibility = View.GONE
                binding.tvAudioMissing.visibility = View.VISIBLE
            }
            else -> {
                // 有录音 → 显示播放器区域
                binding.layoutAudioSection.visibility = View.VISIBLE
                binding.layoutPlayer.visibility = View.VISIBLE
                binding.tvAudioMissing.visibility = View.GONE
                binding.tvAudioTime.text = "准备中…"

                prepareJob = scope.launch {
                    val wav = AudioCacheManager.getPlayableWav(binding.root.context, audioFilePath)
                    if (wav == null) {
                        // 解密/转换失败 → 降级显示缺失
                        binding.layoutAudioSection.visibility = View.GONE
                        binding.layoutPlayer.visibility = View.GONE
                        binding.tvAudioMissing.visibility = View.VISIBLE
                        return@launch
                    }
                    setupPlayer(wav)
                }
            }
        }
    }

    fun release() {
        prepareJob?.cancel()
        prepareJob = null
        resetPlayer()
    }

    // ── 内部实现 ──

    private fun setupPlayer(wav: File) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(wav.path)
                setOnPreparedListener { mp ->
                    binding.btnPlay.isEnabled = true
                    binding.seekAudio.isEnabled = true
                    binding.seekAudio.max = mp.duration
                    binding.seekAudio.progress = 0
                    updateTimeText(0)
                }
                setOnCompletionListener {
                    stopProgressUpdates()
                    binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
                    binding.seekAudio.progress = 0
                    updateTimeText(0)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "播放出错: what=$what extra=$extra")
                    binding.tvAudioTime.text = "播放失败"
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化播放器失败: ${e.message}", e)
            binding.layoutAudioSection.visibility = View.GONE
            binding.layoutPlayer.visibility = View.GONE
            binding.tvAudioMissing.visibility = View.VISIBLE
        }
    }

    private fun togglePlay() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            stopProgressUpdates()
            binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
        } else {
            mp.start()
            startProgressUpdates()
            binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                mediaPlayer?.let {
                    binding.seekAudio.progress = it.currentPosition
                    updateTimeText(it.currentPosition)
                }
                delay(500)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updateTimeText(positionMs: Int) {
        val total = mediaPlayer?.duration ?: 0
        binding.tvAudioTime.text = "${formatMs(positionMs)} / ${formatMs(total)}"
    }

    private fun formatMs(ms: Int): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun resetPlayer() {
        stopProgressUpdates()
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        binding.btnPlay.isEnabled = false
        binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
        binding.seekAudio.isEnabled = false
        binding.seekAudio.progress = 0
    }
}
