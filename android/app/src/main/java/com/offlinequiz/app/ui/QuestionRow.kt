package com.offlinequiz.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinequiz.app.model.*

@Composable
internal fun QuestionRow(
    question: StoredQuestion,
    onClick: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${question.position + 1}. ${QuestionTypes.label(question.type)}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (QuestionReview.needsAttention(question)) {
                    Text(
                        QuestionReview.label(question),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "原编号 ${question.originalNumber ?: "—"} · 来源页 " +
                    question.sourcePages.joinToString("、").ifEmpty { "—" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(question.stem, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            if (QuestionReview.needsAttention(question)) {
                val reasons = buildList {
                    if (!question.confirmed && question.originalNeedsReview) {
                        addAll(reviewReasonLabels(question.originalReviewReasons))
                        if (isEmpty()) add("导入识别有疑问，请对照原页复核")
                    }
                    addAll(QuestionValidator.validate(question))
                    if (!PracticeJudge.isSupportedType(question.type)) add("当前版本不支持该题型")
                    if (question.isTrueFalse && !PracticeJudge.isValidTrueFalseReference(
                            question.toPracticeSnapshot()
                        )) add("判断题答案应为正确或错误")
                }
                Text(
                    reasons.distinct().joinToString("；"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (question.originalReviewReasons.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "导入时 ${question.originalReviewReasons.distinct().size} 项识别提示已保留",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WarmButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) { Text("练习记录") }
            }
        }
    }
}
