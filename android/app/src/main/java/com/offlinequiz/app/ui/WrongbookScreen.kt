package com.offlinequiz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import com.offlinequiz.app.viewmodel.LibraryListViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.offlinequiz.app.ui.MotionAlertDialog as AlertDialog
import com.offlinequiz.app.ui.WarmButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinequiz.app.model.PracticeModes
import com.offlinequiz.app.model.PracticeQuestionStats
import com.offlinequiz.app.model.PracticeSources
import com.offlinequiz.app.model.PracticeState
import com.offlinequiz.app.model.QuestionTypes
import com.offlinequiz.app.viewmodel.ActivePracticeState
import com.offlinequiz.app.viewmodel.WrongbookFilter
import com.offlinequiz.app.viewmodel.WrongbookViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrongbookScreen(
    libraryId: String,
    onBack: () -> Unit,
    onOpenPractice: (String, PracticeState?) -> Unit,
    onOpenHistory: (String) -> Unit,
    onEditQuestion: (String) -> Unit
) {
    val viewModel: WrongbookViewModel = viewModel(key = "wrongbook-$libraryId")
    val libraryList: LibraryListViewModel = viewModel()
    val libraries by libraryList.libraries.collectAsState()
    val libraryName = libraries.firstOrNull { it.library.id == libraryId }?.library?.name
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val keyword = query.trim()
    val visible = viewModel.visible
    val matches = remember(visible, keyword) {
        visible.filter { stats ->
            stats.stem.contains(keyword, ignoreCase = true) ||
                stats.optionTexts.any { it.contains(keyword, ignoreCase = true) }
        }
    }
    var savedFilter by rememberSaveable { mutableStateOf(viewModel.filter.name) }
    LaunchedEffect(libraryId) {
        viewModel.selectFilter(WrongbookFilter.valueOf(savedFilter))
        viewModel.load(libraryId)
    }
    val selectFilter: (WrongbookFilter) -> Unit = { filter ->
        savedFilter = filter.name
        viewModel.selectFilter(filter)
        scope.launch { list.scrollToItem(0) }
    }
    BackHandler {
        if (viewModel.tryLeaveScreen()) onBack()
    }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(viewModel.startedPractice) {
        viewModel.startedPractice?.let { started ->
            viewModel.consumeStartedPractice()
            onOpenPractice(started.sessionId, started.initialState)
        }
    }

    val rePracticeEnabled = viewModel.duePracticableCount > 0 && !viewModel.starting &&
        viewModel.activePracticeState != ActivePracticeState.LOADING &&
        viewModel.activePracticeState != ActivePracticeState.FAILED

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            QuestionSearchTopBar(query, onQueryChange = {
                query = it
                list.requestScrollToItem(0)
            }) {
                Text(libraryName?.let { "$it · 错题" } ?: "错题本", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val report = viewModel.report
        when {
            viewModel.loading && report == null -> Column(
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
                Text(viewModel.error ?: "统计数据读取失败")
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { if (viewModel.tryLeaveScreen()) onBack() }, modifier = Modifier.weight(1f)) { Text("返回上一页") }
                    Button(onClick = viewModel::retry, modifier = Modifier.weight(1f)) { Text("重试") }
                }
            }
            report == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {

                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassFilterButton(
                            selected = viewModel.filter == WrongbookFilter.DUE,
                            onClick = { selectFilter(WrongbookFilter.DUE) },
                            label = { Text("待复习 ${viewModel.due.size}") }
                        )
                        GlassFilterButton(
                            selected = viewModel.filter == WrongbookFilter.ALL,
                            onClick = { selectFilter(WrongbookFilter.ALL) },
                            label = { Text("全部历史错题 ${viewModel.wrongbook.size}") }
                        )
                    }
                
                    GlassPanel(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "错题重练",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "只练当前待复习且可练习的题目；开始时固定题目与顺序，之后题库修改不影响这一轮。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            ActivePracticeStatus(
                                state = viewModel.activePracticeState,
                                activeLabel = viewModel.activePractice?.let { summary ->
                                    "现有未完成：${PracticeSources.label(summary.source)} · " +
                                        "${PracticeModes.label(summary.mode)} · ${summary.progressLabel}"
                                },
                                onRetry = viewModel::retryActivePractice
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassActionButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.startPractice(PracticeModes.SEQUENTIAL) },
                                    enabled = rePracticeEnabled
                                ) { ActionLabel(AppIcons.Sequential, "顺序重练") }
                                GlassActionButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.startPractice(PracticeModes.RANDOM) },
                                    enabled = rePracticeEnabled
                                ) { ActionLabel(AppIcons.Random, "随机重练") }
                            }
                            if (viewModel.starting && !viewModel.startingSingleReview) {
                                    Text(
                                        if (viewModel.committing) {
                                            "正在创建新一轮…"
                                        } else {
                                            "正在确认现有练习会话…"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            if (viewModel.due.isEmpty()) {
                                Text(
                                    "当前没有待复习的错题。答错的题会保留在“全部历史错题”中。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (viewModel.duePracticableCount == 0) {
                                Text(
                                    "待复习题目当前都不可练习（待校对或结构异常），请先去校对。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                val target = viewModel.firstUnpracticableId
                                    ?: viewModel.due.firstOrNull { !it.practicable }?.questionId
                                target?.let { id ->
                                    TextButton(onClick = {
                                        if (viewModel.tryLeaveScreen()) onEditQuestion(id)
                                    }) { Text("去校对题目") }
                                }
                            }
                        }
                    }
                
            Row(Modifier.weight(1f).fillMaxWidth().padding(end = 12.dp)) {
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {






                if (matches.isEmpty()) {
                    item {
                        Text(
                            if (keyword.isNotEmpty()) {
                                "没有找到匹配的错题"
                            } else if (viewModel.filter == WrongbookFilter.DUE) {
                                "没有待复习的错题。"
                            } else {
                                "还没有历史错题；先做几道练习吧。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    }
                } else {
                    items(matches, key = { it.questionId }) { stats ->
                        WrongbookRow(
                            stats = stats,
                            actionsEnabled = !viewModel.committing && !viewModel.starting,
                            onOpenHistory = {
                                if (viewModel.tryLeaveScreen()) onOpenHistory(stats.questionId)
                            },
                            onReview = { viewModel.reviewQuestion(stats.questionId) }
                        )
                    }
                }
            }
            if (matches.isNotEmpty()) {
                QuestionFastScroller(count = matches.size, list = list)
            }
            }
            }
        }
    }

    viewModel.pendingStartMode?.let { mode ->
        val active = viewModel.activePractice
        AlertDialog(
            onDismissRequest = viewModel::cancelPendingStart,
            title = { Text(if (active != null) "已有未完成的练习" else "练习会话已变化") },
            text = {
                val current = active?.let {
                    "现有会话：${PracticeSources.label(it.source)} · ${PracticeModes.label(it.mode)} · " +
                        it.progressLabel
                } ?: if (viewModel.starting) "正在读取现有会话…" else "原有练习会话已结束。"
                val status = when {
                    viewModel.committing -> "\n\n正在创建新一轮…完成前不能取消。"
                    viewModel.starting -> "\n\n正在确认现有练习会话…此时取消会作废本次确认。"
                    else -> ""
                }
                Text(
                    "$current\n\n开始“${PracticeModes.label(mode)}错题重练”会新建一轮，当前会话与已作答历史保留，" +
                        "但不再从“继续练习”进入。未经确认不会替换现有会话。$status"
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { viewModel.confirmStartPractice() },
                        enabled = !viewModel.starting && !viewModel.committing
                    ) { Text("确认开始新一轮") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::cancelPendingStart,
                    enabled = !viewModel.committing
                ) { Text("取消") }
            }
        )
    }

    if (viewModel.committing && !viewModel.startingSingleReview) {
        // Modal wait: covers every touch target (row history/review, top bar, filters) so no
        // navigation callback can slip through while the session write is in flight.
        AlertDialog(
            onDismissRequest = viewModel::notifyCommitInProgress,
            title = { Text("正在创建新一轮") },
            text = { Text("正在写入练习会话，完成前不能取消或离开。") },
            confirmButton = {}
        )
    }
}

@Composable
private fun ActivePracticeStatus(
    state: ActivePracticeState,
    activeLabel: String?,
    onRetry: () -> Unit
) {
    when (state) {
        ActivePracticeState.LOADING -> Text(
            "正在确认现有练习会话…（确认前不能开始新一轮）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ActivePracticeState.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "未能确认现有练习会话，确认前不能开始新一轮。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onRetry) { Text("重试确认会话") }
        }
        ActivePracticeState.PRESENT -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                activeLabel ?: "现有未完成练习",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        ActivePracticeState.NONE -> Unit
    }
}

@Composable
private fun WrongbookRow(
    stats: PracticeQuestionStats,
    actionsEnabled: Boolean,
    onOpenHistory: () -> Unit,
    onReview: () -> Unit
) {
    GlassPanel {
        Column(Modifier.clickable(enabled = actionsEnabled, onClick = onReview).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "第 ${stats.position + 1} 题 · ${QuestionTypes.label(stats.type)} · 原编号 " +
                    (stats.originalNumber ?: "—"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(stats.stemSummary, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            val history = "${stats.historicalWrongCount} 次"
            Text(
                "历史错误：$history",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                "当前状态：${stats.mastery.label}" +
                    if (stats.contentChanged) "（内容已修改）" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (stats.mastery.isDue) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                if (stats.practicable) {
                    "可练习"
                } else {
                    "当前不可练习（待校对或结构异常）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "最近练习：" + (stats.lastPracticeAt?.let { formatTimestamp(it) } ?: "尚无有效作答"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassActionButton(onClick = onOpenHistory, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth()) { ActionLabel(AppIcons.History, "查看历史") }
            }
        }
    }
}

// Filters/practice controls are fixed above the question-only scrolling area.
