package com.offlinequiz.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinequiz.app.model.HistorySnapshotView
import com.offlinequiz.app.model.PracticeHistoryText
import com.offlinequiz.app.model.PracticeResults
import com.offlinequiz.app.model.QuestionHistoryRecord
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.viewmodel.QuestionHistoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionHistoryScreen(
    libraryId: String,
    questionId: String,
    onBack: () -> Unit,
    onEditQuestion: (String) -> Unit
) {
    val viewModel: QuestionHistoryViewModel = viewModel(key = "history-$libraryId-$questionId")
    LaunchedEffect(libraryId, questionId) { viewModel.load(libraryId, questionId) }
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("题目历史") }
            )
        }
    ) { padding ->
        val history = viewModel.history
        when {
            viewModel.loading && history == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
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
                Text(viewModel.error ?: "题目历史读取失败")
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onBack) { Text("返回") }
                    Button(onClick = viewModel::retry) { Text("重试") }
                }
            }
            history == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    val question = history?.question
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "当前题目（题库现有内容）",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                question?.let {
                                    "${QuestionTypes.label(it.type)} · 原编号 ${it.originalNumber ?: "—"}"
                                } ?: "题目已不存在",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                question?.stem ?: "当前题目不在本题库中，以下为历史快照记录。",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            question?.options?.forEach { option ->
                                Text(
                                    "${option.key}. ${option.text}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            history?.stats?.let { stats ->
                                Text(
                                    "当前状态：${stats.mastery.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (stats.mastery.isDue) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    "有效作答 ${stats.totals.effective} 次 · 正确 ${stats.totals.correct} · " +
                                        "错误 ${stats.totals.incorrect} · 正确率 ${stats.totals.accuracyLabel} · 跳过 ${stats.skipped}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "最近练习：" + (stats.lastPracticeAt?.let { formatTimestamp(it) } ?: "尚无有效作答"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (stats.contentChanged) {
                                    Text(
                                        "题目内容已修改：旧记录仍按当时的题干和答案显示，不能证明当前版本已掌握。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            question?.let {
                                TextButton(onClick = { onEditQuestion(it.id) }) { Text("校对/修改题目") }
                            }
                        }
                    }
                }

                val records = history?.records.orEmpty()
                if (records.isEmpty()) {
                    item {
                        Text(
                            "还没有已落盘的作答记录。完成练习后，这里会显示每次的题目快照与结果。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    item {
                        Text(
                            "历史记录（新→旧，共 ${records.size} 条）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(records) { record ->
                        HistoryRecordCard(
                            record = record,
                            showVersionWarning = history?.question != null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(record: QuestionHistoryRecord, showVersionWarning: Boolean) {
    val answer = record.answer
    val snapshotView = remember(record) { HistorySnapshotView.of(record) }
    var expanded by remember(answer.id) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (answer.result) {
                PracticeResults.CORRECT,
                PracticeResults.REMEMBERED -> MaterialTheme.colorScheme.secondaryContainer
                PracticeResults.INCORRECT,
                PracticeResults.FORGOTTEN -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${formatTimestamp(answer.submittedAt)} · " +
                    PracticeHistoryText.kindLabel(answer.kind, answer.result),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "你的答案：" + snapshotView.userAnswer,
                style = MaterialTheme.typography.bodyMedium
            )
            if (showVersionWarning && !record.currentContentVersion) {
                Text(
                    "此记录来自旧内容版本，与当前题目内容不一致。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (snapshotView.available) {
                Text(
                    "当时题目（历史快照）：${snapshotView.typeLabel}" +
                        (snapshotView.originalNumber?.let { " · 原编号 $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                if (!expanded) {
                    Text(
                        snapshotView.stem,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(snapshotView.stem, style = MaterialTheme.typography.bodyMedium)
                    if (snapshotView.options.isNotEmpty()) {
                        Text(
                            "当时选项：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        snapshotView.options.forEach { option ->
                            Text(
                                "${option.key}. ${option.text}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        "当时参考答案：" + snapshotView.referenceAnswer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起当时题目" else "展开当时题目与参考答案")
                }
            } else {
                Text(
                    HistorySnapshotView.UNAVAILABLE_MESSAGE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
