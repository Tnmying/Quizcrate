package com.offlinequiz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinequiz.app.R
import com.offlinequiz.app.BuildConfig
import com.offlinequiz.app.ThemeMode
import com.offlinequiz.app.model.*
import com.offlinequiz.app.viewmodel.LibraryListViewModel
import kotlinx.coroutines.CancellationException

@Composable
internal fun ProfileScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpen: (String) -> Unit
) {
    var showThemeOptions by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("我的") }
        item { ProfileHeader() }
        item { EntryCard("全局学习记录", "汇总所有题库", AppIcons.Stats) { onOpen(AppRouteState.Route.RECORDS) } }
        item { EntryCard("外观", themeMode.label, AppIcons.Appearance) { showThemeOptions = true } }
        item { EntryCard("备份与恢复", "本地文件，妥善保存", AppIcons.Backup) { onOpen(AppRouteState.Route.BACKUP) } }
        item { EntryCard("使用帮助", "", AppIcons.Help) { onOpen(AppRouteState.Route.HELP) } }
        item { EntryCard("关于题匣", "", AppIcons.Info) { onOpen(AppRouteState.Route.ABOUT) } }
    }
    if (showThemeOptions) {
        Dialog(onDismissRequest = { showThemeOptions = false }) {
            GlassPanel(Modifier.clip(RoundedCornerShape(GlassCardRadius)).glassBackdrop()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("外观", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            val selected = mode == themeMode
                            val tint = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .18f)
                                else Color.Transparent
                            Row(
                                Modifier.fillMaxWidth().glassSurface(18.dp, tint = tint)
                                    .selectable(selected = selected, role = Role.RadioButton) {
                                        onThemeModeChange(mode)
                                        showThemeOptions = false
                                    }
                                    .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    GlassActionButton(
                        onClick = { showThemeOptions = false },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("取消") }
                }
            }
        }
    }
}

@Composable
fun LearningOverviewScreen(wrongbook: Boolean, onBack: () -> Unit, onOpenLibrary: (String) -> Unit) {
    val vm: LibraryListViewModel = viewModel()
    val libraries by vm.libraries.collectAsState()
    var stats by remember { mutableStateOf<Map<String, PracticeLibraryStats>?>(null) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(libraries, retry) {
        stats = null; error = false
        try { stats = vm.learningStats(libraries) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    if (!wrongbook) BackHandler(onBack = onBack)
    val loadedStats = stats
    val libraryError = vm.librariesError
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().then(if (wrongbook) Modifier else Modifier.navigationBarsPadding()), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading(if (wrongbook) "错题" else "全局学习记录") }
        if (libraryError != null) item { Text(libraryError); TextButton(onClick = vm::retryLibraries) { Text("重试") } }
        else if (vm.librariesLoading) item { CircularProgressIndicator() }
        else if (error) item { Text("读取学习记录失败"); TextButton(onClick = { retry++ }) { Text("重试") } }
        else if (loadedStats == null) item { CircularProgressIndicator() }
        else {
            val values = loadedStats.values
            if (!wrongbook) item {
                val totals = globalPracticeTotals(values)
                GlassCard {
                    Text("全部 ${libraries.size} 个题库", style = MaterialTheme.typography.titleMedium)
                    Text("累计已刷  ${totals.effective} 题次", style = MaterialTheme.typography.headlineSmall)
                    Text("正确率  ${totals.accuracyLabel}    正确 ${totals.correct} / 错误 ${totals.incorrect}")
                    Text("普通练习完成 ${values.sumOf { it.rounds.normalCompleted }} 遍")
                    Text("历史错题 ${values.sumOf { it.wrongQuestionCount }} 题 · 待复习 ${values.sumOf { it.dueQuestionCount }} 题")
                    Text("含自动判题与自评；跳过不计题量或正确率。各题库完成遍数相加，单题复习不增加遍数。", style = MaterialTheme.typography.bodySmall)
                    if (totals.effective == 0) Text("还没有有效作答记录，开始一次练习吧。")
                }
            }
            val shown = libraries.filter { !wrongbook || (loadedStats[it.library.id]?.wrongQuestionCount ?: 0) > 0 }
            if (wrongbook && shown.isEmpty()) item {
                GlassCard {
                    Image(painterResource(R.drawable.mascot_wrong_empty), null, Modifier.size(100.dp).align(Alignment.CenterHorizontally))
                    Text("暂时没有错题", style = MaterialTheme.typography.titleLarge)
                    Text("练习中的错题会按题库整理在这里。")
                }
            }
            items(shown, key = { it.library.id }) { library ->
                val s = loadedStats[library.library.id] ?: return@items
                EntryCard(library.library.name,
                    if (wrongbook) "待复习 ${s.dueQuestionCount} 题 · 历史错题 ${s.wrongQuestionCount} 题"
                    else "累计 ${s.totals.effective} 题次 · 正确率 ${s.totals.accuracyLabel}",
                    if (wrongbook) AppIcons.Review else AppIcons.Stats) { onOpenLibrary(library.library.id) }
            }
        }
    }
}

@Composable
fun InformationScreen(about: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val uriHandler = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading(if (about) "关于题匣" else "使用帮助") }
        item { GlassCard {
            if (about) {
                Text("题匣 / Quizcrate  ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                Text("完全离线的本地题库练习应用。无需登录，不上传题库或作答记录。")
                Text("项目采用 MIT 许可。第三方组件及许可证见项目仓库中的许可说明。")
                TextButton(onClick = { uriHandler.openUri("https://github.com/Tnmying/Quizcrate") }) { Text("在浏览器查看开源项目") }
            } else {
                Text("1. 在学习页导入文字型 PDF、TXT 或粘贴题目，默认自动识别混合题型。检查预览后保存；推荐格式与示例见下方说明。")
                Text("2. 点击题库可顺序或随机练习，也可继续未完成轮次。首页只显示最近的一次。")
                Text("3. 题库卡片向左滑可查看详情或删除，名称旁的铅笔用于重命名。")
                Text("4. 题目管理支持筛选、关键词搜索、右侧快速滑块和编辑；正常题无需先校对。")
                Text("5. 错题页按题库复习，可查看历史或单题重练。跳过不计有效作答，偷看答案计为错误。")
                Text("6. 更换手机前，在“我的 → 备份与恢复”导出备份。恢复会创建新副本，不覆盖现有题库。")
            }
        } }
        if (!about) importFormatGuideItems()
    }
}
