package com.offlinequiz.app.ui

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.offlinequiz.app.ui.WarmButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinequiz.app.backup.RestoreSummary
import com.offlinequiz.app.viewmodel.BackupUiState
import com.offlinequiz.app.viewmodel.BackupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    onOpenLibrary: (String) -> Unit
) {
    val viewModel: BackupViewModel = viewModel()
    val state = viewModel.state
    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) viewModel.export(uri) }
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.prepare(uri) }
    val leave: () -> Unit = {
        if (viewModel.tryLeave()) onBack()
    }

    BackHandler { leave() }

    LaunchedEffect(state) {
        if (state !is BackupUiState.Committing) viewModel.consumeLeaveBlocked()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text("备份与恢复") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCard {
                Text(
                    "备份文件含题库和练习记录，未加密。导出/恢复都只在本机读写；恢复作为新副本导入，现有题库不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (val current = state) {
                BackupUiState.Idle -> {
                    Button(
                        onClick = {
                            exportPicker.launch(
                                "offlinequiz-backup-${System.currentTimeMillis()}.zip"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("导出全部题库与练习记录") }
                    GlassActionButton(
                        onClick = { importPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从备份文件恢复") }
                }

                BackupUiState.Exporting -> Progress("正在生成并校验备份…")
                BackupUiState.Preparing -> {
                    Progress("正在读取并校验备份…")
                    TextButton(onClick = viewModel::cancelPreview) { Text("取消") }
                }

                is BackupUiState.Preview -> PreviewCard(
                    summary = current.summary,
                    onConfirm = viewModel::confirm,
                    onCancel = viewModel::cancelPreview
                )

                BackupUiState.Committing -> {
                    Progress("正在写入新副本…")
                    Text(
                        "写入期间不能离开；完成后可从首页打开新题库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is BackupUiState.Success -> {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (current.libraryIds.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.tryLeave()
                                onOpenLibrary(current.libraryIds.first())
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("打开新题库") }
                    }
                    TextButton(onClick = leave) { Text("完成") }
                }

                is BackupUiState.Error -> {
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = leave) { Text("返回") }
                }
            }
            if (viewModel.leaveBlocked) {
                Text(
                    "正在写入，不能离开。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun Progress(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator()
        Text(label)
    }
}

@Composable
private fun PreviewCard(
    summary: RestoreSummary,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    GlassPanel {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("恢复预览", style = MaterialTheme.typography.titleMedium)
            Text("题库 ${summary.libraryCount} 个")
            Text("题目 ${summary.questionCount} 题（需处理 ${summary.pendingReviewQuestions}）")
            Text("练习记录 ${summary.sessionCount} 个 · 作答 ${summary.answerCount} 条")
            if (summary.textSourceLibraries > 0) Text("包含文字原文：${summary.textSourceLibraries} 个题库")
            Text(
                "来源 PDF：包含 ${summary.pdfIncludedLibraries} 个，缺失 ${summary.pdfMissingLibraries} 个" +
                    if (summary.pdfMissingLibraries > 0) "（缺失的题库仍可恢复，原页查看不可用）" else ""
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "作为新副本导入，现有题库不变；重复导入会各自生成独立副本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("确认导入为新副本")
            }
            GlassActionButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("取消")
            }
        }
    }
}
