package com.offlinequiz.app.ui

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import com.offlinequiz.app.ui.MotionAlertDialog as AlertDialog
import com.offlinequiz.app.ui.WarmButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.offlinequiz.app.model.PracticeEligibility
import com.offlinequiz.app.model.PracticeLibraryReport
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticeSessionSummary
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.QuestionReview
import com.offlinequiz.app.viewmodel.LibraryDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDetailScreen(
    libraryId: String,
    onBack: () -> Unit,
    onOpenPractice: (String) -> Unit,
    onOpenWrongbook: () -> Unit,
    onOpenQuestions: () -> Unit,
    onOpenReview: () -> Unit
) {
    val viewModel: LibraryDetailViewModel = viewModel(key = "library-$libraryId")
    LaunchedEffect(libraryId) { viewModel.load(libraryId) }

    BackHandler { if (!viewModel.maintaining) onBack() }

    val library = viewModel.library
    val questions = viewModel.questions
    val snackbar = remember { SnackbarHostState() }
    var showDetails by remember { mutableStateOf(false) }
    var endRoundId by remember { mutableStateOf<String?>(null) }
    var resetStats by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(viewModel.startedPracticeId) {
        viewModel.startedPracticeId?.let { sessionId ->
            viewModel.consumeStartedPractice()
            onOpenPractice(sessionId)
        }
    }

    val pending = questions.count(QuestionReview::needsAttention)
    val eligibility = remember(questions) { PracticeEligibility.evaluate(questions) }
    val activePractice = viewModel.activePractice

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Text(
                        library?.name ?: "题库",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                
                actions = {
                    IconButton(onClick = { showDetails = true }, enabled = library != null) {
                        Icon(AppIcons.Info, contentDescription = "详细信息")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (viewModel.loading && library == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                PracticeEntryCard(
                    eligibleCount = eligibility.eligible.size,
                    reviewExcluded = eligibility.reviewExcluded,
                    structureExcluded = eligibility.structureExcluded,
                    unsupportedExcluded = eligibility.unsupportedTypeExcluded,
                    active = activePractice,
                    practiceLoaded = viewModel.practiceLoaded,
                    practiceLoadFailed = viewModel.practiceLoadFailed,
                    starting = viewModel.startingPractice || viewModel.maintaining,
                    onReset = {
                        viewModel.clearMaintenanceError()
                        endRoundId = activePractice?.id
                    },
                    onStartSequential = { viewModel.startPractice(PracticeModes.SEQUENTIAL) },
                    onStartRandom = { viewModel.startPractice(PracticeModes.RANDOM) },
                    onContinue = { activePractice?.let { onOpenPractice(it.id) } },
                    onRetryPractice = viewModel::retryActivePractice,
                    onOpenReview = onOpenReview
                )
            }

            item {
                LibraryStatsCard(
                    stats = viewModel.stats,
                    loading = viewModel.statsLoading ||
                        (viewModel.stats == null && viewModel.loading),
                    error = viewModel.statsError,
                    onRetry = viewModel::retryStats,
                    resetEnabled = library != null && !viewModel.maintaining && !viewModel.startingPractice,
                    onReset = { viewModel.clearMaintenanceError(); resetStats = true },
                    onOpenWrongbook = onOpenWrongbook
                )
            }

            item {
                Button(onClick = onOpenQuestions, modifier = Modifier.fillMaxWidth()) {
                    ActionLabel(AppIcons.Manage, "题目管理 · 查看全部 ${questions.size} 题")
                }
            }
        }
    }

    if (showDetails && library != null) {
        LibraryInformationDialog(library, questions.size, pending) { showDetails = false }
    }

    if (viewModel.maintaining && endRoundId == null && !resetStats) {
        AlertDialog(onDismissRequest = {}, title = { Text("正在保存") },
            text = { Text("正在处理本题库，完成前请稍候…") }, confirmButton = {})
    }
    if (endRoundId != null || resetStats) {
        val endingId = endRoundId
        AlertDialog(
            onDismissRequest = {
                if (!viewModel.maintaining) { endRoundId = null; resetStats = false }
            },
            title = { Text(if (endingId != null) "结束本轮练习？" else "重置统计和错题本？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (endingId != null)
                        "结束当前未完成的一轮，保留已有作答、统计和错题，不增加完成遍数。之后可重新选择顺序或随机练习。"
                    else "清除“${library?.name.orEmpty()}”的全部练习记录、完成遍数和错题，并结束未完成轮次。题目、校对内容和原 PDF 保留。此操作不能撤销。")
                    viewModel.maintenanceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (viewModel.maintaining) Text("正在保存，请稍候…")
                }
            },
            confirmButton = {
                TextButton(enabled = !viewModel.maintaining, onClick = {
                    val done = { endRoundId = null; resetStats = false }
                    if (endingId != null) viewModel.endRound(endingId, done)
                    else viewModel.resetStatistics(done)
                }) { Text(if (endingId != null) "确认结束" else "确认重置") }
            },
            dismissButton = {
                TextButton(enabled = !viewModel.maintaining, onClick = { endRoundId = null; resetStats = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PracticeEntryCard(
    eligibleCount: Int,
    reviewExcluded: Int,
    structureExcluded: Int,
    unsupportedExcluded: Int,
    active: PracticeSessionSummary?,
    practiceLoaded: Boolean,
    practiceLoadFailed: Boolean,
    starting: Boolean,
    onStartSequential: () -> Unit,
    onStartRandom: () -> Unit,
    onContinue: () -> Unit,
    onReset: () -> Unit,
    onRetryPractice: () -> Unit,
    onOpenReview: () -> Unit
) {
    GlassPanel {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("练习", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onReset, enabled = active != null && practiceLoaded && !starting) {
                    Icon(AppIcons.Reset, contentDescription = "结束本轮练习", modifier = Modifier.size(18.dp))
                }
            }
            Text(
                "可练习 $eligibleCount 题",
                style = MaterialTheme.typography.bodyMedium
            )
            if (reviewExcluded + structureExcluded + unsupportedExcluded > 0) {
                Text(
                    "暂不可练习：识别有疑问 $reviewExcluded · 结构异常 $structureExcluded" +
                        if (unsupportedExcluded > 0) " · 未知题型 $unsupportedExcluded" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onOpenReview) { Text("去校对待处理题") }
            }

            if (!practiceLoaded) {
                Text(
                    if (practiceLoadFailed) {
                        "未能确认现有练习状态，确认前不能开始新一轮练习。"
                    } else {
                        "正在读取练习状态…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (practiceLoadFailed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (practiceLoadFailed) {
                    TextButton(onClick = onRetryPractice) { Text("重试读取练习状态") }
                }
            } else if (active != null) {
                Text(
                    "未完成：${PracticeSources.label(active.source)} · " +
                        "${PracticeModes.label(active.mode)} · ${active.progressLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onContinue, enabled = !starting, modifier = Modifier.fillMaxWidth()) {
                    ActionLabel(AppIcons.Play, "继续练习")
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onStartSequential,
                        enabled = eligibleCount > 0 && !starting
                    ) { ActionLabel(AppIcons.Sequential, "顺序练习") }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onStartRandom,
                        enabled = eligibleCount > 0 && !starting
                    ) { ActionLabel(AppIcons.Random, "随机练习") }
                }
                if (eligibleCount == 0) {
                    Text(
                        "题库中没有可练习题目；请先导入题目或完成待校对/结构异常题目的校对。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (starting) {
                Text(
                    "正在创建练习…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

        }
    }
}

@Composable
private fun LibraryStatsCard(
    stats: PracticeLibraryReport?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    resetEnabled: Boolean,
    onReset: () -> Unit,
    onOpenWrongbook: () -> Unit
) {
    GlassPanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("统计", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onReset, enabled = resetEnabled) {
                    Icon(AppIcons.Reset, contentDescription = "重置统计和错题本", modifier = Modifier.size(18.dp))
                }
            }
            when {
                error != null -> {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
                stats == null && loading -> Text(
                    "正在统计…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                stats == null -> Text(
                    "暂无统计数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    val overview = stats.overview
                    Text(
                        "总共已刷 ${overview.rounds.normalCompleted} 遍",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "累计已刷 ${overview.totals.effective} 题",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "正确 ${overview.totals.correct} · 错误 ${overview.totals.incorrect} · " +
                            "正确率 ${overview.totals.accuracyLabel}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "历史错题 ${overview.wrongQuestionCount} · 待复习 ${overview.dueQuestionCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overview.dueQuestionCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loading) {
                            Text(
                                "正在更新…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Button(onClick = onOpenWrongbook, modifier = Modifier.fillMaxWidth()) { ActionLabel(AppIcons.Review, "打开错题本") }
        }
    }
}

