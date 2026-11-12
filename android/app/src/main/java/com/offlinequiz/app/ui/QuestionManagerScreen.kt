package com.offlinequiz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinequiz.app.model.QuestionFilter
import com.offlinequiz.app.viewmodel.LibraryDetailViewModel
import kotlin.math.roundToInt

/** Indexes refer to the current filtered list, not resettable PDF numbering. */
internal object QuestionListPosition {
    fun dragIndex(fraction: Float, count: Int): Int =
        (fraction.coerceIn(0f, 1f) * (count - 1).coerceAtLeast(0)).roundToInt()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionManagerScreen(
    libraryId: String,
    filterName: String,
    onFilterChange: (String) -> Unit,
    onBack: () -> Unit,
    onEditQuestion: (String) -> Unit,
    onOpenHistory: (String) -> Unit
) {
    val viewModel: LibraryDetailViewModel = viewModel(key = "library-$libraryId")
    LaunchedEffect(libraryId) { viewModel.load(libraryId) }
    val filter = QuestionFilter.restore(filterName)
    val questions = viewModel.questions
    var query by rememberSaveable { mutableStateOf("") }
    val keyword = query.trim()
    val filtered = remember(questions, filter, keyword) {
        questions.filter { question ->
            filter.includes(question) && (
                question.stem.contains(keyword, ignoreCase = true) ||
                    question.options.any { it.text.contains(keyword, ignoreCase = true) }
                )
        }
    }
    // Keep the query and scroll position when visiting editing or history.
    val list = rememberLazyListState()
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            QuestionSearchTopBar(query, onQueryChange = {
                query = it
                list.requestScrollToItem(0)
            }) { ActionLabel(AppIcons.Manage, "题目管理") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuestionFilter.entries.forEach { entry ->
                    GlassFilterButton(
                        selected = filter == entry,
                        onClick = {
                            list.requestScrollToItem(0)
                            if (filter != entry) onFilterChange(entry.name)
                        },
                        icon = AppIcons.Filter,
                        label = { Text("${entry.label} ${questions.count(entry::includes)}") }
                    )
                }
            }
            if (viewModel.loading) {
                Text("正在读取题目…", Modifier.padding(16.dp))
            } else if (filtered.isEmpty()) {
                Text(
                    viewModel.message ?: if (keyword.isNotEmpty()) "没有找到匹配的题目"
                    else if (filter == QuestionFilter.ATTENTION)
                        "没有需要处理的题目，可以返回题库继续练习。"
                    else "没有符合筛选条件的题目",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(Modifier.weight(1f).fillMaxWidth().padding(end = 12.dp)) {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { question ->
                            QuestionRow(
                                question = question,
                                onClick = { onEditQuestion(question.id) },
                                onOpenHistory = { onOpenHistory(question.id) }
                            )
                        }
                    }
                    QuestionFastScroller(count = filtered.size, list = list)
                }
            }
        }
    }
}

