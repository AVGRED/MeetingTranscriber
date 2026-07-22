package com.example.mt.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mt.data.repository.MeetingRepository
import com.example.mt.platform.PlatformDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    db: PlatformDatabase,
    onNavigateToMeeting: () -> Unit,
) {
    val meetingRepo = remember(db) {
        MeetingRepository(db.meetingQueries, db.recoveryQueries)
    }

    var totalCount by remember { mutableStateOf(0L) }
    var activeCount by remember { mutableStateOf(0L) }

    // 🔁 每次进入首页都重新加载统计数据
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(Unit, refresh) {
        withContext(Dispatchers.IO) {
            totalCount = meetingRepo.count()
            activeCount = meetingRepo.countActive()
        }
    }

    // 导航到会议页面后，返回时自动刷新
    DisposableEffect(Unit) {
        onDispose { refresh++ }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        // 欢迎区
        Text(
            text = "Meeting Transcriber",
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "桌面会议转写助手",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        // 统计卡片
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                title = "会议总数",
                value = totalCount.toString(),
                icon = Icons.Filled.FolderOpen,
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = "进行中",
                value = activeCount.toString(),
                icon = Icons.Filled.PlayCircle,
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = "已归档",
                value = (totalCount - activeCount).toString(),
                icon = Icons.Filled.Archive,
            )
        }

        Spacer(Modifier.height(48.dp))

        // 开始新会议按钮
        Button(
            onClick = onNavigateToMeeting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("开始新会议", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
