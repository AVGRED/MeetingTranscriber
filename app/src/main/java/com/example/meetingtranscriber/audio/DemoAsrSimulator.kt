package com.example.meetingtranscriber.audio

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 演示模式 — 模拟实时转写，不依赖云 API
 *
 * 模拟 3 人会议对话，逐句输出转写结果（含说话人标签）。
 */
class DemoAsrSimulator {

    var onInterimResult: ((String) -> Unit)? = null
    var onSentenceResult: ((sentenceId: Long, text: String, speakerId: String, startMs: Long, endMs: Long) -> Unit)? = null
    var onConnectionStateChanged: ((Int) -> Unit)? = null

    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var simulationJob: Job? = null

    private val script = listOf(
        Triple("speaker_0", "大家好，今天我们主要讨论三个议题。", 2000L),
        Triple("speaker_0", "第一个是Q2的产品规划方向，第二个是目前的资源分配问题，第三个是下个月的客户拜访安排。", 2500L),
        Triple("speaker_1", "好的，那我先说一下产品这边的情况。", 1800L),
        Triple("speaker_1", "目前Q1的数据已经出来了，整体营收增长了15%，但是有两个产品线没有达到预期。", 2200L),
        Triple("speaker_1", "我觉得Q2应该把重点放在核心产品上，砍掉那些投入产出比不高的项目。", 2000L),
        Triple("speaker_2", "我同意这个方向。从技术角度看，我们现在的资源确实太分散了。", 1800L),
        Triple("speaker_2", "如果集中力量做两三件事，我们的交付质量和速度都能提升。", 2000L),
        Triple("speaker_0", "那我们来具体排一下优先级。", 1500L),
        Triple("speaker_0", "大家觉得哪些项目是必须保留的？", 1800L),
        Triple("speaker_1", "核心产品的迭代肯定是第一优先级，这是我们的基本盘。", 2000L),
        Triple("speaker_2", "还有数据平台的建设也不能停，这个涉及到后面所有业务的数据能力。", 2000L),
        Triple("speaker_0", "对，数据平台确实重要。那我们暂定这两个为P0。", 1800L),
        Triple("speaker_0", "客户拜访的事情呢？下个月有三家重点客户需要去。", 2000L),
        Triple("speaker_1", "我这边已经在安排了。A客户的拜访定在6月5号，B和C还在确认时间。", 2200L),
        Triple("speaker_1", "需要技术这边安排一个同事跟我一起去A客户那边做方案演示。", 2000L),
        Triple("speaker_2", "没问题，我安排一下。A客户主要关注的是数据安全方面对吧？", 1800L),
        Triple("speaker_1", "对，他们最关心的就是数据安全和系统稳定性。", 1800L),
        Triple("speaker_2", "那我带小张过去，他对安全这块最熟。", 1500L),
        Triple("speaker_0", "好，那就这么定了。", 1200L),
        Triple("speaker_0", "最后我总结一下今天的结论。", 1500L),
        Triple("speaker_0", "第一，Q2聚焦核心产品，砍掉低价值项目。第二，数据平台建设继续推进。第三，6月5号A客户拜访，技术部安排小张参加。", 2500L),
        Triple("speaker_0", "大家还有其他要补充的吗？", 1500L),
        Triple("speaker_1", "没有了，谢谢大家。", 1200L),
        Triple("speaker_2", "没有，会议结束。", 1200L),
    )

    fun start() {
        isRunning.set(true)
        onConnectionStateChanged?.invoke(1)

        simulationJob = scope.launch {
            delay(500)
            onConnectionStateChanged?.invoke(2)
            delay(500)

            var sentenceIdx = 0L
            var totalMs = 0L

            for ((speakerId, text, pauseAfter) in script) {
                if (!isRunning.get()) break

                val words = simulateWordByWord(text)
                for (word in words) {
                    if (!isRunning.get()) break
                    onInterimResult?.invoke(word)
                    delay(80 + (Math.random() * 120).toLong())
                }

                val endMs = totalMs + 500
                onSentenceResult?.invoke(sentenceIdx, text, speakerId, totalMs, endMs)

                sentenceIdx++
                totalMs = endMs + pauseAfter

                if (pauseAfter > 0) {
                    delay(pauseAfter)
                }
            }

            if (isRunning.get()) {
                onConnectionStateChanged?.invoke(0)
            }
        }
    }

    private fun simulateWordByWord(text: String): List<String> {
        val result = mutableListOf<String>()
        var current = ""
        for (char in text) {
            current += char
            result.add(current)
        }
        return result
    }

    fun stop() {
        isRunning.set(false)
        simulationJob?.cancel()
        simulationJob = null
    }
}
