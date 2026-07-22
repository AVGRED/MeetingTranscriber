package com.example.mt.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mt.data.model.MeetingInfo
import com.example.mt.data.repository.MeetingRepository
import com.example.mt.platform.PlatformDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(db: PlatformDatabase) {
    val meetingRepo = remember(db) {
        MeetingRepository(db.meetingQueries, db.recoveryQueries)
    }

    var meetings by remember { mutableStateOf<List<MeetingInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var selectedMeeting by remember { mutableStateOf<MeetingInfo?>(null) }

    // 加载会议列表（搜索查询做 300ms 防抖，避免每次按键都查库）
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }
    LaunchedEffect(showArchived, debouncedQuery) {
        withContext(Dispatchers.IO) {
            meetings = if (debouncedQuery.isNotBlank()) {
                meetingRepo.search(debouncedQuery)
            } else if (showArchived) {
                meetingRepo.getArchived()
            } else {
                meetingRepo.getActive()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("会议历史", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        // 搜索栏 + 归档切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索会议...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
            FilterChip(
                selected = showArchived,
                onClick = { showArchived = !showArchived },
                label = { Text("已归档") },
                leadingIcon = if (showArchived) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else null,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (meetings.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("暂无会议记录", style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(meetings, key = { it.id }) { meeting ->
                    MeetingCard(
                        meeting = meeting,
                        onClick = { selectedMeeting = meeting },
                    )
                }
            }
        }
    }

    // 会议详情弹窗
    selectedMeeting?.let { meeting ->
        MeetingDetailDialog(
            meeting = meeting,
            onDismiss = { selectedMeeting = null },
        )
    }
}

@Composable
private fun MeetingCard(
    meeting: MeetingInfo,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meeting.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = meeting.date,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = meeting.formattedDuration,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!meeting.tag.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text(meeting.tag) },
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MeetingDetailDialog(
    meeting: MeetingInfo,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(meeting.title) },
        text = {
            Column {
                DetailRow("日期", meeting.date)
                DetailRow("时间", meeting.formattedStartTime)
                DetailRow("时长", meeting.formattedDuration)
                DetailRow("说话人数", "${meeting.speakerCount} 人")
                DetailRow("转写段落", "${meeting.segmentCount} 条")
                DetailRow("识别引擎", meeting.asrEngineType ?: "未知")
                DetailRow("纪要引擎", meeting.llmEngineType ?: "未知")
                meeting.summary?.let { summary ->
                    Spacer(Modifier.height(12.dp))
                    Text("会议纪要", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
