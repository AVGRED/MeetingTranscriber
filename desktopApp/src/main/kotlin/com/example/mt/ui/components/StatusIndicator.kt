package com.example.mt.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 状态指示灯：说话中有绿色脉冲动画，暂停中显示黄色。
 * 仅在说话时运行动画，避免非说话状态下 60fps 无效 GPU 消耗。
 */
@Composable
fun StatusIndicator(
    isSpeaking: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    // 仅在说话模式才创建无限循环动画；其它情况直接用静态值 1f
    val pulseScale: Float = if (isSpeaking) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        )
        animated
    } else {
        1f
    }

    val dotColor = when {
        isPaused -> Color(0xFFF9A825)   // 黄色：暂停
        isSpeaking -> Color(0xFF2E7D32) // 绿色：说话中
        else -> Color(0xFF9E9E9E)       // 灰色：无声音
    }

    val statusText = when {
        isPaused -> "已暂停"
        isSpeaking -> "说话中"
        else -> "等待语音"
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
