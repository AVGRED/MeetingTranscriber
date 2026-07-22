package com.example.mt.audio

/**
 * 播放/分享用音频缓存（expect 声明）。
 *
 * 录音文件有两种格式（见 WavRecorder）：
 * - "RIFF"：标准明文 WAV，可直接播放/下载，原样返回
 * - "MTEW"：平台加密（20 字节头 + 加密 PCM），外部无法读取，
 *   解密转成标准 WAV 缓存到 cacheDir/decrypted_audio/
 *
 * - Android actual: EncryptedFile 解密 + LRU 缓存（≤300MB / 7 天）
 * - Desktop actual: 仅处理 RIFF 明文（不加密 = 无需解密缓存）
 */
expect object AudioCacheManager {

    /**
     * 获取可播放/可下载的标准 WAV 文件路径。
     *
     * - RIFF 明文 → 原样返回源文件路径
     * - MTEW 加密 → 解密转标准 WAV（缓存复用，同一源文件只转一次）
     * - 文件缺失/解密失败 → null
     */
    suspend fun getPlayableWav(audioFilePath: String): String?

    /**
     * 按额度修剪解密缓存（App 启动时调用）。
     * 先删 .tmp 残留与超龄（7 天）文件，仍超额（300MB）则按 LRU 淘汰。
     */
    fun trim()
}
