package com.offlinequiz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinequiz.app.model.QuestionFilter
import com.offlinequiz.app.viewmodel.LibraryDetailViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Indexes refer to the current filtered list, not resettable PDF numbering. */
internal object QuestionListPosition {
    fun jumpIndex(text: String, count: Int): Int? =
        text.toIntOrNull()?.takeIf { it in 1..count }?.minus(1)

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
    BackHandler(onBack = onBack)
    val filter = QuestionFilter.restore(filterName)
    val questions = viewModel.questions
    val filtered = remember(questions, filter) { questions.filter(filter::includes) }
    // The navigation-level SaveableStateProvider retains index and pixel offset
    // while editing/history screens are composed, including rotation/process restoration.
    val list = rememberLazyListState()
    val seek = rememberFastScrollSeek(list)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var target by rememberSaveable { mutableStateOf("") }
    var jumpError by rememberSaveable { mutableStateOf(false) }
    val jump = {
        val index = QuestionListPosition.jumpIndex(target, filtered.size)
        jumpError = index == null
        if (index != null) {
            focus.clearFocus()
            scope.launch { list.scrollToItem(index) }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("题目管理") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuestionFilter.entries.forEach { entry ->
                    FilterChip(
                        selected = filter == entry,
                        onClick = {
                            if (filter == entry) scope.launch { list.scrollToItem(0) }
                            else onFilterChange(entry.name)
                            target = ""
                            jumpError = false
                        },
                        label = { Text("${entry.label} ${questions.count(entry::includes)}") }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it.filter(Char::isDigit).take(9); jumpError = false },
                    label = { Text("跳至第几题") },
                    supportingText = {
                        Text(if (jumpError) "请输入 1～${filtered.size}"
                            else "按当前列表顺序，共 ${filtered.size} 题")
                    },
                    isError = jumpError,
                    enabled = filtered.isNotEmpty(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { jump() }),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { jump() }, enabled = filtered.isNotEmpty()) { Text("跳转") }
            }

            if (viewModel.loading) {
                Text("正在读取题目…", Modifier.padding(16.dp))
            } else if (filtered.isEmpty()) {
                Text(
                    viewModel.message ?: if (filter == QuestionFilter.ATTENTION)
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
                    QuestionFastScroller(
                        count = filtered.size,
                        currentIndex = if (!list.canScrollForward && list.canScrollBackward)
                            filtered.lastIndex else list.firstVisibleItemIndex,
                        onSeek = seek
                    )
                }
            }
        }
    }
}

