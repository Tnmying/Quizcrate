package com.offlinequiz.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
internal fun SourceTextDialog(text: String, selectedLines: Set<Int> = emptySet(), onDismiss: () -> Unit) {
    val lines = remember(text) { text.split(Regex("\r\n|\n|\r")) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = ((selectedLines.minOrNull() ?: 1) - 1).coerceIn(0, lines.lastIndex))
    Dialog(onDismissRequest = onDismiss) {
        GlassPanel {
            Column(Modifier.padding(18.dp).fillMaxWidth().fillMaxHeight(.85f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("导入原文", style = MaterialTheme.typography.titleLarge)
                Text("蓝色行号为本题来源；这里保留导入时的文字。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(lines) { index, line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}", color = if (index + 1 in selectedLines) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp))
                            SelectionContainer { Text(line.ifEmpty { " " }, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                GlassActionButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}
