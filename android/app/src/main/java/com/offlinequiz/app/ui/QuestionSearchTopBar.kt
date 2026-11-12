package com.offlinequiz.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuestionSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    title: @Composable () -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    TopAppBar(
        title = title,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        actions = {
            IconButton(
                onClick = { open = true },
                modifier = Modifier.padding(end = 12.dp).glassSurface(24.dp)
            ) {
                Icon(
                    AppIcons.Search,
                    contentDescription = if (query.isBlank()) "搜索题目" else "搜索题目，筛选中",
                    tint = if (query.isBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
                )
            }
        }
    )
    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            GlassPanel(Modifier.clip(RoundedCornerShape(GlassCardRadius)).glassBackdrop()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("搜索题目", style = MaterialTheme.typography.titleLarge)
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth().glassSurface(18.dp).focusRequester(focus),
                        placeholder = { Text("输入题干或选项关键词") },
                        leadingIcon = { Icon(AppIcons.Search, null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            { IconButton(onClick = { onQueryChange("") }) { Icon(AppIcons.Close, "清空关键词") } }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { open = false }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    GlassActionButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End)) {
                        Text("完成")
                    }
                }
            }
        }
    }
}
