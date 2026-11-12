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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
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
    // navigating to the old library before the newly selected source starts parsing.
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
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("导入题库") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
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
                    TextButton(onClick = { viewModel.reset() }, modifier = Modifier.weight(1f)) {
                        Text(if (state.imported.sourceText != null) "修改原文" else "重新选择")
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
                viewModel = viewModel,
                onPick = { picker.launch(arrayOf("application/pdf", "text/plain")) }
            )
            ImportUiState.Parsing -> BusyImport(
                modifier = Modifier.padding(padding),
                title = "正在本地识别题目…",
                detail = "内容只在本机处理，识别后可预览并保存。"
            )
            ImportUiState.Saving -> BusyImport(
                modifier = Modifier.padding(padding),
                title = "正在保存题库…",
                detail = "正在保存来源内容并写入本地数据库。"
            )
            is ImportUiState.Error -> IdleImport(
                modifier = Modifier.padding(padding),
                viewModel = viewModel,
                error = current.message,
                onPick = { picker.launch(arrayOf("application/pdf", "text/plain")) }
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
private fun IdleImport(modifier: Modifier, viewModel: ImportViewModel, error: String? = null, onPick: () -> Unit) {
    var showAdvanced by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(viewModel.inputText.isNotBlank()) }
    var showFormatGuide by remember { mutableStateOf(false) }
    if (showFormatGuide) ImportFormatGuideDialog { showFormatGuide = false }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            GlassPanel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("导入文字题库", style = MaterialTheme.typography.titleLarge)
                    Text(if (viewModel.defaultType == "unknown") "自动识别混合题型，内容只在本机处理。" else "已启用自定义识别设置，可在高级选项中调整。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onPick) { Text("选择 PDF / TXT") }
                        TextButton(onClick = { showPaste = !showPaste }) { Text(if (showPaste) "收起输入" else "粘贴题目") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showFormatGuide = true }) { Text("格式说明") }
                        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "收起高级选项" else "高级选项") }
                    }
                    if (showAdvanced) {
                        ImportChoice("识别方式", viewModel.defaultType, listOf(
                            "unknown" to "自动识别混合题型", "single_choice" to "无标记题目按单选识别",
                            "multiple_choice" to "无标记题目按多选识别", "true_false" to "无标记题目按判断识别",
                            "fill_blank" to "无标记题目按填空识别", "short_answer" to "无标记题目按简答识别"
                        )) { viewModel.defaultType = it }
                        ImportChoice("TXT 编码", viewModel.encoding, listOf("自动（UTF-8）" to "自动（UTF-8 / BOM）", "GB18030" to "GB18030")) { viewModel.encoding = it }
                        Text("扫描件、图片和 Word 暂不支持。填空可用 ____ 标记，多空答案用 || 分隔。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (showPaste) item {
            GlassPanel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("或粘贴题目", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = viewModel.inputText,
                        onValueChange = { if (it.length <= 512 * 1024) viewModel.inputText = it },
                        label = { Text("题目原文") },
                        placeholder = { Text("一、单选题\n1、哪项是水的化学式？\nA、H2O\nB、CO2\n答案：A") },
                        minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = viewModel::importText, enabled = viewModel.inputText.isNotBlank()) { Text("识别并预览") }
                }
            }
        }
    }
}

@Composable
private fun ImportChoice(label: String, value: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { expanded = true }, modifier = Modifier.glassSurface(16.dp)) {
            Text(choices.firstOrNull { it.first == value }?.second.orEmpty())
        }
        if (expanded) Dialog(onDismissRequest = { expanded = false }) {
            GlassPanel {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    choices.forEach { (key, title) ->
                        TextButton(onClick = { onSelect(key); expanded = false }, modifier = Modifier.fillMaxWidth()) {
                            Text((if (key == value) "✓ " else "") + title,
                                color = if (key == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
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
private fun PreviewImport(
    modifier: Modifier,
    state: ImportUiState.Preview,
    name: String,
    onNameChange: (String) -> Unit
) {
    val questions = state.imported.result.questions
    var showSource by remember { mutableStateOf(false) }
    if (showSource) state.imported.sourceText?.let { text -> SourceTextDialog(text) { showSource = false } }
    val pending = questions.count { it.needsReview }
    val samples = questions.take(50)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            GlassPanel {
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
                    if (state.imported.sourceText != null) TextButton(onClick = { showSource = true }) { Text("核对完整原文") }
                    Text(
                        if (state.imported.sourceText != null) "共 ${questions.size} 题 · 文字来源已保留"
                        else "共 ${questions.size} 题 · ${state.imported.pageCount} 页 · 下划线 ${state.imported.underlineCount} 条（匹配 ${state.imported.matchedUnderlineCount}）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    val unparsed = state.imported.result.anomalies.filter { it.type == "unparsed_text" || it.type == "unparsed_answer_table" }
                    if (unparsed.isNotEmpty()) {
                        Text("有 ${unparsed.size} 行未归类，请核对原文。", color = MaterialTheme.colorScheme.error)
                        unparsed.take(5).forEach { Text("第 ${it.line} 行：${it.detail}", style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    }
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
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${QuestionTypes.label(question.type)} · 原编号 ${question.originalNumber}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(question.stem, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    question.options.forEach { Text("${it.key}. ${it.text}", style = MaterialTheme.typography.bodySmall, maxLines = 3) }
                    Text("参考答案：${question.answers.joinToString("；").ifEmpty { "未识别" }}", style = MaterialTheme.typography.bodySmall, maxLines = 4)
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
