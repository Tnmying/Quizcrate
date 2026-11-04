package com.offlinequiz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.offlinequiz.app.ui.WarmButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinequiz.app.model.PracticeAnswerKinds
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticePhases
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeRecordedAnswer
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.PracticeState
import com.offlinequiz.app.model.ShortAnswerMatchResult
import com.offlinequiz.app.model.ShortAnswerMatchUnavailable
import com.offlinequiz.app.model.ShortAnswerTextMatch
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.viewmodel.PracticeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    sessionId: String,
    onBack: () -> Unit,
    onCorrectQuestion: (String) -> Unit
) {
    val viewModel: PracticeViewModel = viewModel(key = "practice-$sessionId")
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    BackHandler { viewModel.requestExit(onBack) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (PracticeSources.isWrongbook(viewModel.state?.session?.source.orEmpty())) {
                            PracticeSources.label(viewModel.state?.session?.source.orEmpty())
                        } else {
                            "练习"
                        }
                    )
                },
                actions = {
                    if (viewModel.state?.currentQuestion != null) {
                        TextButton(
                            onClick = { viewModel.requestCorrection(onCorrectQuestion) },
                            enabled = !viewModel.saving && !viewModel.exiting && !viewModel.loading
                        ) { Text("纠错/校正本题") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when {
            viewModel.loading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            viewModel.error != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(viewModel.error ?: "练习读取失败")
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onBack, enabled = !viewModel.exiting, modifier = Modifier.weight(1f)) { Text("返回题库") }
                    OutlinedButton(onClick = viewModel::retry, enabled = !viewModel.exiting, modifier = Modifier.weight(1f)) {
                        Text("重试")
                    }
                }
            }
            viewModel.state == null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            else -> {
                val state = viewModel.state!!
                if (state.session.phase == PracticePhases.COMPLETED) {
                    PracticeResultView(state = state, onBack = onBack, modifier = Modifier.padding(padding))
                } else {
                    val question = state.currentQuestion
                    if (question == null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("题目数据不完整，请返回题库重新开始")
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onBack) { Text("返回题库") }
                        }
                    } else {
                        PracticeQuestionView(
                            modifier = Modifier.padding(padding),
                            state = state,
                            question = question,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PracticeQuestionView(
    modifier: Modifier,
    state: PracticeState,
    question: PracticeQuestionSnapshot,
    viewModel: PracticeViewModel
) {
    val scroll = rememberScrollState()
    LaunchedEffect(state.session.currentIndex, state.session.phase) { scroll.scrollTo(0) }
    val isFeedback = state.session.phase == PracticePhases.FEEDBACK
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp)
            .then(rememberRevealModifier(question.questionId)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "第 ${(state.session.currentIndex + 1).coerceAtMost(state.session.total)} / ${state.session.total} 题 · " +
                "${PracticeModes.label(state.session.mode)}练习",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${question.typeLabel} · 原编号 ${question.originalNumber ?: "—"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(question.stem, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (viewModel.draftSaveFailed) {
            Text(
                "草稿保存失败：当前输入仍保留在屏幕上；请修改或返回重试。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (isFeedback) {
            FeedbackView(
                question = question,
                feedback = state.feedback,
                saving = viewModel.saving,
                isLast = state.session.currentIndex == state.session.total - 1,
                shortAnswerMatch = viewModel.shortAnswerMatch,
                onNext = viewModel::next
            )
        } else {
            AnsweringView(question = question, viewModel = viewModel)
        }
    }
}

@Composable
private fun AnsweringView(
    question: PracticeQuestionSnapshot,
    viewModel: PracticeViewModel
) {
    val enabled = !viewModel.saving && !viewModel.exiting
    when (question.type) {
        QuestionTypes.SINGLE_CHOICE,
        QuestionTypes.MULTIPLE_CHOICE -> {
            Text(
                if (question.type == QuestionTypes.SINGLE_CHOICE) "选择一个答案" else "选择多个答案（全对才正确）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            question.options.forEach { option ->
                val selected = viewModel.draft.selectedKeys.any {
                    it.equals(option.key, ignoreCase = true)
                }
                WarmOptionRow(
                    selected = selected,
                    enabled = enabled,
                    multiple = question.type == QuestionTypes.MULTIPLE_CHOICE,
                    onClick = { viewModel.selectOption(option.key) }
                ) {
                    if (question.type == QuestionTypes.SINGLE_CHOICE) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            enabled = enabled
                        )
                    } else {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            enabled = enabled
                        )
                    }
                    Text("${option.key}. ${option.text}")
                }
            }
        }

        QuestionTypes.TRUE_FALSE -> {
            Text(
                "选择一个答案",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TrueFalseRow(
                label = "√ 正确",
                selected = viewModel.draft.selectedKeys.contains("√"),
                enabled = enabled,
                onClick = { viewModel.selectTrueFalse(true) }
            )
            TrueFalseRow(
                label = "× 错误",
                selected = viewModel.draft.selectedKeys.contains("×"),
                enabled = enabled,
                onClick = { viewModel.selectTrueFalse(false) }
            )
        }

        QuestionTypes.FILL_BLANK -> {
            Text(
                "按顺序填写每一个空；所有空都匹配才整题正确。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (index in question.answers.indices) {
                OutlinedTextField(
                    value = viewModel.draft.blankAnswers.getOrNull(index).orEmpty(),
                    onValueChange = { viewModel.updateBlank(index, it) },
                    label = { Text("第 ${index + 1} 空") },
                    enabled = enabled && !viewModel.draft.referenceRevealed,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        QuestionTypes.SHORT_ANSWER -> {
            OutlinedTextField(
                value = viewModel.draft.text,
                onValueChange = viewModel::updateShortText,
                label = { Text("你的作答（可选）") },
                minLines = 3,
                enabled = enabled && !viewModel.draft.referenceRevealed,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (question.type == QuestionTypes.FILL_BLANK || question.type == QuestionTypes.SHORT_ANSWER) {
        if (!viewModel.draft.referenceRevealed) {
            TextButton(
                onClick = viewModel::revealReference,
                enabled = enabled
            ) { Text("展开参考答案（心中作答）") }
        } else {
            ReferencePanel(question)
            Text(
                "看完参考答案后，请按真实情况自评；展开后离开不会自动算正确。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (question.type == QuestionTypes.SHORT_ANSWER) {
                ShortAnswerMatchPanel(viewModel.shortAnswerMatch)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.selfAssess(true) },
                    enabled = viewModel.canSelfAssess()
                ) { Text("记得") }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.selfAssess(false) },
                    enabled = viewModel.canSelfAssess()
                ) { Text("没记住") }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (PracticeJudgeUi.isAutomatic(question.type) && !viewModel.draft.referenceRevealed) {
            Button(
                onClick = viewModel::submitAutomatic,
                enabled = viewModel.canSubmitAutomatic(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Text(if (viewModel.saving) "保存中…" else "提交答案")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::skip, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("跳过本题")
            }
            OutlinedButton(onClick = viewModel::peek, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("偷看答案")
            }
        }
    }
    if (viewModel.saving) {
        Text(
            "正在保存作答，请稍候…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrueFalseRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    WarmOptionRow(selected = selected, enabled = enabled, onClick = onClick) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun WarmOptionRow(
    selected: Boolean,
    enabled: Boolean,
    multiple: Boolean = false,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = if (multiple) {
        Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox) { onClick() }
    } else {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().then(interaction).heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
private fun ReferencePanel(question: PracticeQuestionSnapshot) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("参考答案", fontWeight = FontWeight.Bold)
            ReferenceText(question)
        }
    }
}

/**
 * Display-only character overlap. Never a pass/fail judgement: the copy explicitly says it only
 * measures text overlap and that the user decides "记得/没记住".
 */
@Composable
private fun ShortAnswerMatchPanel(match: ShortAnswerMatchResult?) {
    if (match == null) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "文字匹配辅助（仅供参考）",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            if (match.available) {
                Text(
                    "文字匹配度 ${match.percent}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "仅比较文字重合，不代表答案正确；请自行判断。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (match.singleCharacterFallback) {
                    Text(
                        "文本过短，单字比较参考有限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (match.truncated) {
                    Text(
                        "文本较长，仅比较开头部分（最多 ${ShortAnswerTextMatch.MAX_CODE_POINTS} 个有效字符）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    match.unavailableMessage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (match.truncated) {
                    Text(
                        "文本较长，仅检查了开头部分（最多 ${ShortAnswerTextMatch.MAX_CODE_POINTS} 个有效字符）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun ShortAnswerMatchResult.unavailableMessage(): String = when (unavailableReason) {
    ShortAnswerMatchUnavailable.USER_TEXT_EMPTY ->
        "尚未输入文字，无法比较文字重合；仍可直接自评或跳过。"
    ShortAnswerMatchUnavailable.REFERENCE_EMPTY ->
        "参考答案为空，无法计算文字匹配度。"
    ShortAnswerMatchUnavailable.NORMALIZED_USER_EMPTY ->
        "输入去除空白和标点后没有可比较的文字。"
    ShortAnswerMatchUnavailable.NORMALIZED_REFERENCE_EMPTY ->
        "参考答案去除空白和标点后没有可比较的文字。"
    null -> "当前无法计算文字匹配度。"
}

@Composable
private fun FeedbackView(
    question: PracticeQuestionSnapshot,
    feedback: PracticeRecordedAnswer?,
    saving: Boolean,
    isLast: Boolean,
    shortAnswerMatch: ShortAnswerMatchResult?,
    onNext: () -> Unit
) {
    if (feedback == null) {
        Text("反馈读取失败，请返回题库重新打开。", color = MaterialTheme.colorScheme.error)
        return
    }
    if (feedback.kind == PracticeAnswerKinds.SKIPPED) {
        Text("本题已跳过，不计作答。")
        Button(onClick = onNext, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(if (saving) "正在进入下一题…" else if (isLast) "完成本轮" else "进入下一题")
        }
        return
    }
    val title = if (feedback.kind == PracticeAnswerKinds.PEEKED) "已偷看答案 · 计入错题" else when (feedback.result) {
        PracticeResults.CORRECT -> "回答正确"
        PracticeResults.INCORRECT -> "回答错误"
        PracticeResults.REMEMBERED -> "自评：记得"
        PracticeResults.FORGOTTEN -> "自评：没记住"
        PracticeResults.SKIPPED -> "本题已跳过"
        else -> "已记录"
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (feedback.result) {
                PracticeResults.CORRECT,
                PracticeResults.REMEMBERED -> MaterialTheme.colorScheme.secondaryContainer
                PracticeResults.INCORRECT,
                PracticeResults.FORGOTTEN -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "本题已提交并锁定，不能重复提交。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("你的答案：${formatUserAnswer(question, feedback)}")
            Text(
                if (feedback.kind == PracticeAnswerKinds.SELF_ASSESSMENT) "参考内容：" else "正确答案："
            )
            ReferenceText(question)
            if (feedback.kind == PracticeAnswerKinds.SELF_ASSESSMENT) {
                Text(
                    "记得计入正确，没记住计入错误；结果来源仍保留在历史记录中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (question.type == QuestionTypes.SHORT_ANSWER) {
                    ShortAnswerMatchPanel(shortAnswerMatch)
                }
            }
        }
    }
    Button(
        onClick = onNext,
        enabled = !saving,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (saving) "保存中…" else if (isLast) "完成本轮" else "下一题")
    }
}

@Composable
private fun ReferenceText(question: PracticeQuestionSnapshot) {
    when (question.type) {
        QuestionTypes.SINGLE_CHOICE,
        QuestionTypes.MULTIPLE_CHOICE -> Text(question.answers.joinToString("、").ifEmpty { "未设置" })
        QuestionTypes.TRUE_FALSE -> {
            val value = question.answers.firstOrNull()?.trim().orEmpty()
            Text(
                when (value) {
                    "√", "✓", "✔", "对", "正确", "T", "TRUE", "true" -> "正确（√）"
                    "×", "✗", "✘", "错", "错误", "F", "FALSE", "false" -> "错误（×）"
                    else -> value.ifEmpty { "未设置" }
                }
            )
        }
        QuestionTypes.FILL_BLANK -> {
            question.answers.forEachIndexed { index, answer ->
                Text("第 ${index + 1} 空：$answer")
                val accepted = question.acceptableAnswers.getOrNull(index).orEmpty()
                if (accepted.isNotEmpty()) {
                    Text(
                        "可接受：${accepted.joinToString("；")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        QuestionTypes.SHORT_ANSWER -> Text(question.referenceAnswer.orEmpty().ifEmpty { "未设置" })
        else -> Text("未设置")
    }
}

private fun formatUserAnswer(
    question: PracticeQuestionSnapshot,
    feedback: PracticeRecordedAnswer
): String {
    val answer: PracticeUserAnswer = feedback.userAnswer
    return when (question.type) {
        QuestionTypes.SINGLE_CHOICE,
        QuestionTypes.MULTIPLE_CHOICE -> answer.selectedKeys.joinToString("、").ifEmpty { "未作答" }
        QuestionTypes.TRUE_FALSE -> when (answer.selectedKeys.firstOrNull()) {
            "√" -> "正确（√）"
            "×" -> "错误（×）"
            else -> "未作答"
        }
        QuestionTypes.FILL_BLANK -> if (answer.blankAnswers.isEmpty()) {
            "未作答"
        } else {
            answer.blankAnswers.mapIndexed { index, value ->
                "第 ${index + 1} 空：${value.ifBlank { "（空）" }}"
            }.joinToString("；")
        }
        QuestionTypes.SHORT_ANSWER -> answer.text.ifBlank { "未输入文字" }
        else -> "未作答"
    }
}

@Composable
private fun PracticeResultView(state: PracticeState, onBack: () -> Unit, modifier: Modifier) {
    val summary = state.summary
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val singleReview = state.session.source == PracticeSources.SINGLE_REVIEW
        Text(if (singleReview) {
            if (summary.totals.correct > 0) "本题已复习" else "本题练习结束"
        } else "本轮完成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (PracticeSources.isWrongbook(state.session.source)) {
            Text(PracticeSources.label(state.session.source), style = MaterialTheme.typography.labelLarge)
        }
        Text("共 ${summary.total} 题", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本轮已刷 ${summary.totals.effective} 题")
                Text("正确 ${summary.totals.correct} · 错误 ${summary.totals.incorrect}")
                Text("正确率 ${summary.totals.accuracyLabel}")
                Text("跳过：${summary.skipped}")
                Text(
                    if (singleReview) "答对或自评记得即解除待复习；历史错误保留，单题复习不计整库遍数。"
                    else if (PracticeSources.isWrongbook(state.session.source)) "错题重练不计遍数；跳过不计题量或正确率。"
                    else "本轮已计为一遍，包含跳过的题；跳过不计题量或正确率。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(if (singleReview) "返回错题本" else "返回题库")
        }
    }
}

/** UI-only helper; judging itself lives in pure PracticeJudge. */
private object PracticeJudgeUi {
    fun isAutomatic(type: String): Boolean = when (type) {
        QuestionTypes.SINGLE_CHOICE,
        QuestionTypes.MULTIPLE_CHOICE,
        QuestionTypes.TRUE_FALSE,
        QuestionTypes.FILL_BLANK -> true
        else -> false
    }
}
