package com.example.mt.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ControlButtons(
    isMeetingActive: Boolean,
    isPaused: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!isMeetingActive) {
            // 开始按钮
            FilledIconButton(
                onClick = onStart,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "开始", tint = MaterialTheme.colorScheme.onPrimary)
            }
        } else {
            // 暂停 / 继续
            if (isPaused) {
                FilledIconButton(
                    onClick = onResume,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "继续")
                }
            } else {
                FilledIconButton(
                    onClick = onPause,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = "暂停")
                }
            }

            // 停止按钮
            FilledIconButton(
                onClick = onStop,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = "结束", tint = MaterialTheme.colorScheme.onError)
            }
        }
    }
}
