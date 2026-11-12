package com.offlinequiz.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.offlinequiz.app.R
import com.offlinequiz.app.model.PracticeSessionSummary
import kotlinx.coroutines.CancellationException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.offlinequiz.app.ui.WarmButton as Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
    onImportPdf: (Uri?) -> Unit,
    onOpenLibrary: (String) -> Unit,
    onContinue: (PracticeSessionSummary) -> Unit
) {
    val viewModel: LibraryListViewModel = viewModel()
    val libraries by viewModel.libraries.collectAsState()
    var latest by remember { mutableStateOf<PracticeSessionSummary?>(null) }
    var practiceError by remember { mutableStateOf(false) }
    var retry by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(libraries, retry) {
        latest = null; practiceError = false
        try { latest = viewModel.latestPractice(libraries) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { practiceError = true }
    }
    val libraryListState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<LibrarySummary?>(null) }
    var information by remember { mutableStateOf<LibrarySummary?>(null) }
    var deleteTarget by remember { mutableStateOf<LibrarySummary?>(null) }
    var revealedId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = revealedId != null) { if (!viewModel.deleting) revealedId = null }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { PageHeading("学习") }
                TextButton(
                    onClick = { onImportPdf(null) },
                    modifier = Modifier.glassSurface(24.dp)
                ) { ActionLabel(AppIcons.Import, "导入题库") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (viewModel.librariesError != null) {
                Text(viewModel.librariesError!!, Modifier.padding(24.dp))
                TextButton(onClick = viewModel::retryLibraries) { Text("重试") }
            } else if (viewModel.librariesLoading) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(24.dp))
            } else if (libraries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(painterResource(R.drawable.mascot_import_empty), null, Modifier.size(120.dp))
                    Text("还没有本地题库", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "导入文字型 PDF、TXT 或粘贴题目，在手机本地识别并保存。识别异常会保留为待校对项。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = libraryListState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (practiceError) item {
                        Text("暂时无法读取续刷记录")
                        TextButton(onClick = { retry++ }) { Text("重试") }
                    }
                    latest?.let { session -> item(key = "continue") {
                        GlassCard {
                            BoxWithConstraints {
                                val portraitSize = minOf(maxWidth * .25f, 88.dp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("上次学到", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                        Text(libraries.firstOrNull { it.library.id == session.libraryId }?.library?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text(session.progressLabel)
                                        LinearProgressIndicator(
                                            progress = { if (session.total > 0) session.currentIndex.toFloat() / session.total else 0f },
                                            modifier = Modifier.fillMaxWidth(),
                                            drawStopIndicator = {}
                                        )
                                    }
                                    Image(painterResource(R.drawable.mascot_home), null, Modifier.size(portraitSize))
                                }
                            }
                            Button(onClick = { onContinue(session) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(AppIcons.Play, null, Modifier.size(18.dp)); Text(" 继续刷题")
                            }
                        }
                    } }
                    item { Text("我的题库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
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
    GlassPanel(modifier) {
        Column(
            Modifier.clickable(onClick = onOpen).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(AppIcons.Book, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    summary.library.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRename) {
                    Icon(RenameIcon, contentDescription = "重命名题库", modifier = Modifier.size(18.dp))
                }
            }
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
