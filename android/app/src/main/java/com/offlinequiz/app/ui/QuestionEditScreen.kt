package com.offlinequiz.app.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.offlinequiz.app.ui.MotionAlertDialog as AlertDialog
import com.offlinequiz.app.ui.WarmButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.model.StoredQuestion
import com.offlinequiz.app.model.QuestionReview
import com.offlinequiz.app.viewmodel.QuestionEditViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionEditScreen(
    libraryId: String,
    questionId: String,
    onBack: () -> Unit,
    fromPractice: Boolean = false
) {
    val viewModel: QuestionEditViewModel = viewModel(key = "edit-$questionId")
    val context = LocalContext.current
    LaunchedEffect(libraryId, questionId) { viewModel.load(libraryId, questionId) }

    val question = viewModel.question
    var showPdf by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val pdfFile: File? = remember(question?.id, viewModel.library?.pdfRelativePath) {
        viewModel.pdfFile()
    }

    BackHandler {
        if (viewModel.saving) return@BackHandler
        if (viewModel.hasUnsavedChanges()) confirmDiscard = true else onBack()
    }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(if (fromPractice) "校正题库中的本题" else "编辑题目") }
            )
        },
        bottomBar = {
            if (question != null) {
                GlassPanel(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.save(false) },
                            enabled = !viewModel.saving
                        ) { Text("仅保存") }
                        Button(
                            onClick = { viewModel.save(true) },
                            enabled = !viewModel.saving,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (viewModel.saving) "保存中…" else "保存并确认")
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            viewModel.loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            question == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(viewModel.errors.firstOrNull() ?: "题目无法打开")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("返回题库") }
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (fromPractice) {
                    GlassPanel {
                        Text(
                            "这里修改题库中的当前版本，可能与本轮题目不同。保存并确认后，新建的练习会使用修改后的内容；当前轮次继续使用原题和原答案，已有作答记录不重判。返回可继续本轮，也可回题库另开新一轮。",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                HeaderCard(question = question, libraryName = viewModel.library?.name, textSource = viewModel.library?.sourceText != null, onViewPdf = {
                    if (viewModel.library?.sourceText != null) {
                        showText = true
                    } else if (pdfFile == null) {
                        Toast.makeText(context, "原 PDF 私有副本不可用", Toast.LENGTH_SHORT).show()
                    } else {
                        showPdf = true
                    }
                })

                if (viewModel.errors.isNotEmpty()) {
                    ErrorCard(viewModel.errors)
                }

                if (viewModel.hasUnsavedChanges() && question.confirmed) {
                    GlassPanel(tint = MaterialTheme.colorScheme.secondary.copy(alpha = .12f)) {
                        Text(
                            "已修改题目内容，保存后需要重新确认。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                OriginalEvidenceCard(question)
                EditorForm(question, viewModel)
            }
        }
    }

    if (showPdf && pdfFile != null) {
        PdfPageDialog(
            file = pdfFile,
            pages = question?.sourcePages.orEmpty(),
            onDismiss = { showPdf = false }
        )
    }
    if (showText) viewModel.library?.sourceText?.let { text ->
        SourceTextDialog(text, question?.sourceLines?.map { it.line }?.toSet().orEmpty()) { showText = false }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回后本次编辑不会保留，已保存的内容不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            }
        )
    }
}

@Composable
private fun HeaderCard(question: StoredQuestion, libraryName: String?, textSource: Boolean, onViewPdf: () -> Unit) {
    GlassPanel {
        Column(Modifier.padding(14.dp)) {
            Text(
                "${QuestionTypes.label(question.type)} · 原编号 ${question.originalNumber ?: "—"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                (if (textSource) "来源行：${question.sourceLines.firstOrNull()?.line ?: "—"}～${question.sourceLines.lastOrNull()?.line ?: "—"}"
                else "来源页：${question.sourcePages.joinToString("、").ifEmpty { "—" }}") +
                    (libraryName?.let { " · 题库：$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onViewPdf) { Text(if (textSource) "查看原文" else "查看原页") }
                Spacer(Modifier.weight(1f))
                if (QuestionReview.needsAttention(question)) {
                    Text(
                        QuestionReview.label(question),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(errors: List<String>) {
    GlassPanel(tint = MaterialTheme.colorScheme.error.copy(alpha = .12f)) {
        Column(Modifier.padding(14.dp)) {
            Text("保存前请修正", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun OriginalEvidenceCard(question: StoredQuestion) {
    var expanded by remember(question.id) { mutableStateOf(false) }
    GlassPanel {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "导入时识别记录",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (question.originalNeedsReview) {
                    "原始提示：" + reviewReasonLabels(question.originalReviewReasons).joinToString("；")
                } else {
                    "导入时未标记识别异常。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text("原始题干：${question.originalStem}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "原始答案：${question.originalAnswers.joinToString("、").ifEmpty { "未识别" }}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (question.originalOptions.isNotEmpty()) {
                    Text(
                        "原始选项：" + question.originalOptions.joinToString("；") { "${it.key}.${it.text}" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (question.originalBlanks.isNotEmpty()) {
                    val crossLine = question.originalBlanks.count { it.crossLine }
                    Text(
                        "原始下划线区域 ${question.originalBlanks.size} 个，学习空 " +
                            "${question.originalBlanks.sumOf { it.learningBlanks.size }} 个" +
                            if (crossLine > 0) "，其中跨行合并 $crossLine 个" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "以上是导入时的不可变记录，用于排查；编辑后的内容单独保存。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EditorForm(question: StoredQuestion, viewModel: QuestionEditViewModel) {
    var showTypePicker by remember(question.id) { mutableStateOf(false) }
    if (showTypePicker) Dialog(onDismissRequest = { showTypePicker = false }) {
        GlassPanel {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("调整本题题型", style = MaterialTheme.typography.titleMedium)
                listOf(QuestionTypes.SINGLE_CHOICE, QuestionTypes.MULTIPLE_CHOICE, QuestionTypes.TRUE_FALSE,
                    QuestionTypes.FILL_BLANK, QuestionTypes.SHORT_ANSWER).forEach { type ->
                    TextButton(enabled = !viewModel.saving, onClick = { viewModel.updateType(type); showTypePicker = false }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (question.type == type) "✓ " else "") + QuestionTypes.label(type),
                            color = if (question.type == type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
    GlassPanel {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("题型：${QuestionTypes.label(question.type)}", modifier = Modifier.weight(1f))
                TextButton(enabled = !viewModel.saving, onClick = { showTypePicker = true }) { Text("调整") }
            }
            OutlinedTextField(
                colors = glassFieldColors(),
                value = question.stem,
                onValueChange = viewModel::updateStem,
                label = { Text("题干") },
                minLines = 3,
                enabled = !viewModel.saving,
                modifier = Modifier.fillMaxWidth()
            )

            when (question.type) {
                QuestionTypes.SINGLE_CHOICE,
                QuestionTypes.MULTIPLE_CHOICE -> ChoiceEditor(question, viewModel)
                QuestionTypes.TRUE_FALSE -> TrueFalseEditor(question, viewModel)
                QuestionTypes.FILL_BLANK -> FillBlankEditor(question, viewModel)
                QuestionTypes.SHORT_ANSWER -> OutlinedTextField(
                    colors = glassFieldColors(),
                    value = question.referenceAnswer.orEmpty(),
                    onValueChange = viewModel::updateReferenceAnswer,
                    label = { Text("参考答案") },
                    minLines = 4,
                    enabled = !viewModel.saving,
                    modifier = Modifier.fillMaxWidth()
                )
                else -> Text(
                    "这道题的题型暂不明确，可点击上方“调整”选择题型后校正内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ChoiceEditor(question: StoredQuestion, viewModel: QuestionEditViewModel) {
    val enabled = !viewModel.saving
    Text("选项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    question.options.forEachIndexed { index, option ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                colors = glassFieldColors(),
                value = option.key,
                onValueChange = { viewModel.updateOptionKey(index, it) },
                label = { Text("标记") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.width(76.dp)
            )
            OutlinedTextField(
                colors = glassFieldColors(),
                value = option.text,
                onValueChange = { viewModel.updateOptionText(index, it) },
                label = { Text("选项内容") },
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { viewModel.removeOption(index) },
                enabled = enabled && question.options.size > 2
            ) { Text("删除") }
        }
    }
    TextButton(onClick = viewModel::addOption, enabled = enabled) { Text("添加选项") }

    Spacer(Modifier.height(2.dp))
    Text(
        if (question.type == QuestionTypes.SINGLE_CHOICE) "正确项（单选只能选一个）" else "正确项（多选至少两个）",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        question.options.forEach { option ->
            val selected = question.answers.any { it.trim().equals(option.key.trim(), ignoreCase = true) }
            FilterChip(
                selected = selected,
                onClick = { viewModel.toggleChoiceAnswer(option.key) },
                label = { Text(option.key.ifBlank { "?" }) },
                enabled = enabled
            )
        }
    }
}

@Composable
private fun TrueFalseEditor(question: StoredQuestion, viewModel: QuestionEditViewModel) {
    val enabled = !viewModel.saving
    val answer = question.answers.firstOrNull()?.trim().orEmpty()
    Text("判断答案", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(
            selected = isTrueAnswer(answer),
            onClick = { viewModel.setTrueFalse("√") },
            label = { Text("√ 正确") },
            enabled = enabled
        )
        FilterChip(
            selected = isFalseAnswer(answer),
            onClick = { viewModel.setTrueFalse("×") },
            label = { Text("× 错误") },
            enabled = enabled
        )
    }
    if (answer.isNotEmpty() && !isTrueAnswer(answer) && !isFalseAnswer(answer)) {
        Text(
            "当前保留的答案：$answer（选择上方按钮可改为标准符号）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FillBlankEditor(question: StoredQuestion, viewModel: QuestionEditViewModel) {
    val enabled = !viewModel.saving
    val crossLine = question.blanks.count { it.crossLine }
    Text(
        "学习空 ${question.answers.size} 个 · 原始下划线区域 ${question.blanks.size} 个" +
            if (crossLine > 0) " · 跨行合并 $crossLine 个" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "题干中的 ____ 是空位。增加、删除或合并空会同步修改题干和答案列表；保存时空位数量必须与答案组数一致。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    question.answers.forEachIndexed { index, answer ->
        GlassPanel {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "第 ${index + 1} 空",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.mergeBlank(index) },
                        enabled = enabled && index > 0
                    ) { Text("并入上一空") }
                    TextButton(
                        onClick = { viewModel.removeBlank(index) },
                        enabled = enabled && question.answers.size > 1
                    ) { Text("删除") }
                }
                OutlinedTextField(
                    colors = glassFieldColors(),
                    value = answer,
                    onValueChange = { viewModel.updateBlankAnswer(index, it) },
                    label = { Text("答案") },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "可接受答案（可选，逐条添加）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                question.acceptableAnswers.getOrNull(index).orEmpty().forEachIndexed { acceptedIndex, accepted ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            colors = glassFieldColors(),
                            value = accepted,
                            onValueChange = {
                                viewModel.updateAcceptableAnswer(index, acceptedIndex, it)
                            },
                            label = { Text("可接受答案 ${acceptedIndex + 1}") },
                            enabled = enabled,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.removeAcceptableAnswer(index, acceptedIndex) },
                            enabled = enabled
                        ) { Text("删除") }
                    }
                }
                TextButton(onClick = { viewModel.addAcceptableAnswer(index) }, enabled = enabled) {
                    Text("添加可接受答案")
                }
            }
        }
    }

    Button(onClick = viewModel::addBlank, enabled = enabled) { Text("添加一个空") }
}

private fun isTrueAnswer(value: String): Boolean {
    return value in setOf("√", "✓", "✔", "对", "正确", "T", "TRUE", "true")
}

private fun isFalseAnswer(value: String): Boolean {
    return value in setOf("×", "✗", "✘", "错", "错误", "F", "FALSE", "false")
}
