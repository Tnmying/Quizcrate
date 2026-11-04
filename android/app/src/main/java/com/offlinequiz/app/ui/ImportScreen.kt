package com.offlinequiz.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.offlinequiz.app.ui.WarmButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinequiz.app.ImportUiState
import com.offlinequiz.app.ImportViewModel
import com.offlinequiz.app.model.QuestionTypes
import pdfprobe.Model

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    sessionId: Int,
    initialUri: Uri?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    // A new import session gets a fresh ViewModel. This prevents a previous "Saved" state from
    // navigating to the old library before the newly selected PDF starts parsing.
    val viewModel: ImportViewModel = viewModel(key = "import-$sessionId")
    val state = viewModel.state
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importPdf(uri) }

    LaunchedEffect(initialUri) {
        if (initialUri != null) viewModel.importPdf(initialUri)
    }
    if (state is ImportUiState.Saved) {
        LaunchedEffect(state.libraryId) { onSaved(state.libraryId) }
    }

    val leave: () -> Unit = { viewModel.requestBack(onBack) }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入题库") }
            )
        },
        bottomBar = {
            if (state is ImportUiState.Preview) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.weight(1f)) {
                        Text("重新选择")
                    }
                    Button(
                        onClick = { viewModel.saveLibrary() },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存到本地") }
                }
            }
        }
    ) { padding ->
        when (val current = state) {
            ImportUiState.Idle -> IdleImport(
                modifier = Modifier.padding(padding),
                onPick = { picker.launch(arrayOf("application/pdf")) }
            )
            ImportUiState.Parsing -> BusyImport(
                modifier = Modifier.padding(padding),
                title = "正在本地解析 PDF…",
                detail = "文件只在本机处理，解析完成后才会复制私有副本。"
            )
            ImportUiState.Saving -> BusyImport(
                modifier = Modifier.padding(padding),
                title = "正在保存题库…",
                detail = "正在复制 PDF 私有副本并写入本地数据库。"
            )
            is ImportUiState.Error -> ErrorImport(
                modifier = Modifier.padding(padding),
                message = current.message,
                onPick = { picker.launch(arrayOf("application/pdf")) }
            )
            is ImportUiState.Preview -> PreviewImport(
                modifier = Modifier.padding(padding),
                state = current,
                name = viewModel.libraryName,
                onNameChange = viewModel::updateLibraryName
            )
            is ImportUiState.Saved -> BusyImport(
                modifier = Modifier.padding(padding),
                title = "题库已保存",
                detail = "正在打开题库…"
            )
        }
    }
}

@Composable
private fun IdleImport(modifier: Modifier, onPick: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("选择文字型 PDF", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "扫描件、图片题和公式题暂不支持。导入后先保存为本地题库，再逐题校对。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick) { Text("选择 PDF") }
    }
}

@Composable
private fun BusyImport(modifier: Modifier, title: String, detail: String) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(title)
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorImport(modifier: Modifier, message: String, onPick: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("导入未完成", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(22.dp))
        Button(onClick = onPick) { Text("重新选择 PDF") }
    }
}

@Composable
private fun PreviewImport(
    modifier: Modifier,
    state: ImportUiState.Preview,
    name: String,
    onNameChange: (String) -> Unit
) {
    val questions = state.imported.result.questions
    val pending = questions.count { it.needsReview }
    val samples = questions.take(50)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = { Text("题库名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        state.imported.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "共 ${questions.size} 题 · ${state.imported.pageCount} 页 · " +
                            "下划线 ${state.imported.underlineCount} 条（匹配 ${state.imported.matchedUnderlineCount}）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        typeSummary(questions),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "待校对 $pending 题",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pending > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "保存后仍可修改名称和题目；识别异常会作为原始记录保留。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.saveError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            Text(
                "识别预览（前 ${samples.size} 题）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        items(samples, key = { it.id }) { question ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${QuestionTypes.label(question.type)} · 原编号 ${question.originalNumber}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(question.stem, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    if (question.needsReview) {
                        Text(
                            "识别提示：${reviewReasonLabels(question.reviewReasons).joinToString("；")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        if (questions.size > samples.size) {
            item {
                Text(
                    "其余 ${questions.size - samples.size} 题保存后可在题库详情中查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun typeSummary(questions: List<Model.Question>): String {
    return listOf(
        QuestionTypes.SINGLE_CHOICE,
        QuestionTypes.MULTIPLE_CHOICE,
        QuestionTypes.TRUE_FALSE,
        QuestionTypes.FILL_BLANK,
        QuestionTypes.SHORT_ANSWER
    ).joinToString(" · ") { type ->
        "${QuestionTypes.label(type)} ${questions.count { it.type == type }}"
    }
}
