package com.offlinequiz.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlinequiz.app.model.LibraryRecord

@Composable
internal fun LibraryInformationDialog(library: LibraryRecord, count: Int, pending: Int, onDismiss: () -> Unit) {
    MotionAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("详细信息") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(library.name, style = MaterialTheme.typography.titleMedium)
                Text("共 $count 题 · 需处理 $pending")
                Text("来源：${library.sourceFileName}（${library.sourcePageCount} 页）")
                Text("创建：${formatTimestamp(library.createdAt)}")
                Text("更新：${formatTimestamp(library.updatedAt)}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}
