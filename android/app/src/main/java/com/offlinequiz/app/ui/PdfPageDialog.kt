package com.offlinequiz.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.offlinequiz.app.data.PdfPageRenderer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PdfPageDialog(
    file: File,
    pages: List<Int>,
    onDismiss: () -> Unit
) {
    val safePages = remember(pages) { pages.distinct().sorted().ifEmpty { listOf(1) } }
    var index by remember(safePages) { mutableStateOf(0) }
    val pageNumber = safePages[index.coerceIn(0, safePages.lastIndex)]
    val bitmapState = remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file, pageNumber) {
        loading = true
        error = null
        bitmapState.value = null
        val result = withContext(Dispatchers.IO) {
            runCatching { PdfPageRenderer.render(file, pageNumber) }
        }
        result.onSuccess { bitmapState.value = it }
            .onFailure { error = it.message ?: "无法预览原页" }
        loading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            bitmapState.value?.recycle()
            bitmapState.value = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize().then(rememberRevealModifier(pageNumber))) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("原 PDF 来源页", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "第 $pageNumber 页 · 仅渲染当前页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF2B2B2B)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        loading -> CircularProgressIndicator(color = Color.White)
                        error != null -> Text(
                            error.orEmpty(),
                            color = Color.White,
                            modifier = Modifier.padding(24.dp)
                        )
                        bitmapState.value != null -> Image(
                            bitmap = bitmapState.value!!.asImageBitmap(),
                            contentDescription = "原 PDF 第 $pageNumber 页",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { index -= 1 },
                        enabled = index > 0
                    ) { Text("上一页") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "第 ${index + 1} / ${safePages.size} 个来源页",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { index += 1 },
                        enabled = index < safePages.lastIndex
                    ) { Text("下一页") }
                }
            }
        }
    }
}
