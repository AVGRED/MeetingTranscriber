package com.example.mt.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.mt.network.ConnectionState

/** 连接状态横幅颜色 */
private fun ConnectionState.color(): Color = when (this) {
    ConnectionState.CONNECTED -> Color(0xFF2E7D32)
    ConnectionState.CONNECTING -> Color(0xFFF9A825)
    ConnectionState.RECONNECTING -> Color(0xFFE65100)
    ConnectionState.DISCONNECTED -> Color(0xFF616161)
    ConnectionState.FAILED -> Color(0xFFC62828)
}

private fun ConnectionState.icon() = when (this) {
    ConnectionState.CONNECTED -> Icons.Filled.CloudDone
    ConnectionState.CONNECTING -> Icons.Filled.CloudSync
    ConnectionState.RECONNECTING -> Icons.Filled.CloudSync
    ConnectionState.DISCONNECTED -> Icons.Filled.CloudOff
    ConnectionState.FAILED -> Icons.Filled.CloudOff
}

private fun ConnectionState.label(): String = when (this) {
    ConnectionState.CONNECTED -> "已连接"
    ConnectionState.CONNECTING -> "连接中..."
    ConnectionState.RECONNECTING -> "重连中..."
    ConnectionState.DISCONNECTED -> "未连接"
    ConnectionState.FAILED -> "连接失败"
}

@Composable
fun ConnectionBanner(
    state: ConnectionState,
    engineName: String,
    modifier: Modifier = Modifier,
) {
    val bgColor by animateColorAsState(targetValue = state.color(), label = "bannerColor")

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = bgColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                state.icon(),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = state.label(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            if (engineName.isNotBlank()) {
                Text(
                    text = "· $engineName",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}
