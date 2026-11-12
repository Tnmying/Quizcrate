package com.offlinequiz.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private data class FormatSection(val title: String, val body: String, val example: String? = null)

private val formatSections = listOf(
    FormatSection("支持哪些文件", "支持有文字层的 PDF、TXT 和粘贴文本。文档应已有题目及参考答案；普通教材、讲义不会自动生成题目或答案。"),
    FormatSection("推荐怎样整理", "一份文件可以混合单选、多选、判断、填空、简答，默认自动识别，无需先指定整库题型。\n\n推荐每题以题号开头，并加 [单选题] 等标记，识别会更稳。可用 1.、1、（1）、第1题；选项推荐 A.、B.、C.。参考答案另起一行写“答案：”或“正确答案：”。“我的答案”不会作为标准答案。"),
    FormatSection("单选题", "正确答案写一个选项字母。", "1.[单选题]2＋2 等于几？\nA.3\nB.4\n正确答案：B"),
    FormatSection("多选题", "正确答案写多个选项字母，如 AC 或 A、C。", "2.[多选题]下列哪些数是偶数？\nA.2\nB.3\nC.4\n正确答案：AC"),
    FormatSection("判断题", "推荐用“对 / 错”或“√ / ×”标注参考答案。", "3.[判断题]一周有七天。\n正确答案：对"),
    FormatSection("填空题", "用 ____ 或空括号 () 标出空位。不同空的答案按顺序用 || 分隔；同一空的可替换写法用反斜线 \\ 分隔，任一种都可判对。也支持 (1)、(2) 逐项列出答案。\n\n已有 PDF 可以使用下划线标出的答案；自行整理时推荐显式空位和答案。", "4.[填空题]一周有____天，一天有____小时。\n答案：7\\七||24\\二十四"),
    FormatSection("简答题", "题干与参考答案分开写，推荐答案单独成段。简答不自动判对，按参考答案自行评估。", "5.[简答题]写出一种节约用水的方法。\n参考答案：刷牙时关闭水龙头。"),
    FormatSection("答案集中在文末怎么办", "支持“答案表 / 参考答案”后的题号对应答案，一行可列多题。题号全库唯一最稳；章节或题型重置编号时，答案表应写明对应章节、题型，名称与题目部分保持一致。不能唯一对应时会提示校对。", "答案表\n1.B 2.AC 3.对"),
    FormatSection("文件整理与限制", "推荐单栏、顺序清楚的排版。TXT 推荐 UTF-8；支持带 BOM 的 UTF-16，也可在高级选项手动选择 GB18030。TXT / 粘贴内容单次不超过 512 KB、20000 行。\n\n暂不支持扫描 PDF、照片、图片题、公式题、加密 PDF，以及直接导入 Word、Excel、CSV、PPT。双栏、复杂表格等版式不保证可靠识别，建议先整理成推荐格式。"),
    FormatSection("导入后检查什么", "检查预览中的题数、题型、选项和答案。正常题可以直接练习；缺答案、有冲突或匹配不明的题保留为待处理，可保存后逐题调整，并回看来源原文。不会凭空补题或补答案。")
)

internal fun LazyListScope.importFormatGuideItems() {
    formatSections.forEach { section ->
        item {
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    Text(section.body, style = MaterialTheme.typography.bodyMedium)
                    section.example?.let { example ->
                        SelectionContainer {
                            Text(example, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImportFormatGuideDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        GlassPanel {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("支持格式与示例", style = MaterialTheme.typography.titleLarge)
                Text("以下是推荐格式，题型标记不是必填。长按示例可复制。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { importFormatGuideItems() }
                GlassActionButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}
