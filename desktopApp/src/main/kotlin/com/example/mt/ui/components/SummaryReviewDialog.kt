package com.example.mt.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SummaryReviewDialog(
    summary: String,
    meetingId: Long,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editedSummary by remember(summary) { mutableStateOf(summary) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会议纪要") },
        text = {
            Column {
                Text(
                    "会议 #$meetingId 的 AI 生成纪要，您可以编辑后保存：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = editedSummary,
                    onValueChange = { editedSummary = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(editedSummary) }) {
                Text("保存纪要")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}
