package com.offlinequiz.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.offlinequiz.app.ui.WarmButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinequiz.app.model.LibrarySummary
import com.offlinequiz.app.viewmodel.LibraryListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onImportPdf: (Uri) -> Unit,
    onOpenLibrary: (String) -> Unit,
    onBackupRestore: () -> Unit
) {
    val viewModel: LibraryListViewModel = viewModel()
    val libraries by viewModel.libraries.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<LibrarySummary?>(null) }
    var information by remember { mutableStateOf<LibrarySummary?>(null) }
    var deleteTarget by remember { mutableStateOf<LibrarySummary?>(null) }
    var revealedId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = revealedId != null) { if (!viewModel.deleting) revealedId = null }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportPdf(uri) }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("我的题库", fontWeight = FontWeight.Bold)
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "共 ${libraries.size} 个题库",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onBackupRestore) { Text("备份与恢复") }
            }
            Button(
                onClick = { picker.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 52.dp)
            ) { Text("＋ 导入新题库") }
            Spacer(Modifier.height(16.dp))

            if (libraries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有本地题库", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "选择文字型 PDF，在手机本地识别题目，命名后保存。识别异常会保留为待校对项。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(libraries, key = { it.library.id }) { summary ->
                        SwipeLibraryCard(
                            revealed = revealedId == summary.library.id,
                            onReveal = { revealedId = if (it) summary.library.id else null },
                            onDetails = { revealedId = null; information = summary },
                            onDelete = {
                                revealedId = null
                                viewModel.clearDeleteError()
                                deleteTarget = summary
                            }
                        ) { swipeModifier ->
                            LibraryCard(
                                summary = summary,
                                modifier = swipeModifier,
                                onOpen = {
                                    if (revealedId != null) revealedId = null
                                    else onOpenLibrary(summary.library.id)
                                },
                                onRename = { revealedId = null; renaming = summary }
                            )
                        }
                    }
                }
            }
        }
    }

    information?.let { selected ->
        val latest = libraries.firstOrNull { it.library.id == selected.library.id } ?: selected
        LibraryInformationDialog(latest.library, latest.questionCount, latest.pendingCount) { information = null }
    }
    deleteTarget?.let { target ->
        MotionAlertDialog(
            onDismissRequest = { if (!viewModel.deleting) deleteTarget = null },
            title = { Text("删除题库？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("删除“${target.library.name}”的全部题目、校对内容、练习记录、统计、错题和 PDF 私有副本。其他题库和手机上的原始 PDF 不受影响。此操作不能撤销。")
                    viewModel.deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (viewModel.deleting) Text("正在删除，请稍候…")
                }
            },
            confirmButton = {
                TextButton(enabled = !viewModel.deleting, onClick = {
                    viewModel.deleteLibrary(target.library.id) { deleteTarget = null }
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !viewModel.deleting, onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    if (viewModel.deleting && deleteTarget == null) {
        // A rotation may dismiss local confirmation state while the retained ViewModel commits.
        MotionAlertDialog(onDismissRequest = {}, title = { Text("正在删除题库") },
            text = { Text("正在保存，完成前请稍候…") }, confirmButton = {})
    }

    renaming?.let { summary ->
        RenameLibraryDialog(
            initialName = summary.library.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> viewModel.renameLibrary(summary.library.id, name) }
        )
    }
}

@Composable
private fun LibraryCard(
    summary: LibrarySummary,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onRename: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.library.name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRename) {
                    Icon(RenameIcon, contentDescription = "重命名题库", modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("共 ${summary.questionCount} 题", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "需处理 ${summary.pendingCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (summary.pendingCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

private val RenameIcon = ImageVector.Builder(
    name = "Rename", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)) {
        moveTo(3f, 17.25f); lineTo(3f, 21f); lineTo(6.75f, 21f)
        lineTo(17.81f, 9.94f); lineTo(14.06f, 6.19f); close()
        moveTo(20.71f, 7.04f); lineTo(16.96f, 3.29f)
        lineTo(15.13f, 5.12f); lineTo(18.88f, 8.87f); close()
    }
}.build()
