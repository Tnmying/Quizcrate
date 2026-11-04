# pdf-probe：阶段 01 PDF 提取验证

这个小工具只做验证，不是应用代码。它用 Apache PDFBox 2.0.27 读取真实样本，按字形坐标重建文本行，分离水平描边线段作为下划线，并生成统一题目 JSON。没有调用 AI、没有硬编码样本题干/答案/题量。

## 环境

已在以下环境实际运行：

- Windows 11，PowerShell 5.1
- JetBrains Runtime（JBR）21.0.9，`javac 21.0.9`
- Apache PDFBox 2.0.27、FontBox 2.0.27、Commons Logging 1.2
- 网络只在首次运行时用于从 Maven Central 下载上述三个 jar；之后完全本地运行

Android SDK、Gradle、adb、Android Studio 在本机均未安装，因此本工具只验证 JVM 解析路径，不能宣称 Android 实机通过。

## 运行

在项目根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run.ps1 `
  -InputFile local-inputs/sample.pdf `
  -OutDir work/phase-01
```

如果自动找不到 JDK，显式传入：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run.ps1 `
  -Jdk "C:\Program Files\JetBrains\PyCharm 2025.3.2.1\jbr"
```

可选参数：

- `-RenderPages "1,20,21,23"`：渲染这些页为 PNG；传空字符串可关闭。
- `-NoRender`：不渲染。
- `-OutDir work/phase-01-repro`：换输出目录做重复性检查。

脚本会：

1. 查找 JDK，不修改 `JAVA_HOME` 或系统设置；
2. 把三个 jar 下载到 `tools/pdf-probe/lib/`，逐个校验 SHA-256；
3. 用 `javac` 编译到 `tools/pdf-probe/build/`；
4. 运行 `pdfprobe.PdfProbe` 并把运行命令写入 `<out>/raw/run-command.txt`。

## 运行测试

正式回归是可失败的：任何断言失败都会打印 `FAIL` 并返回非零。它会先用 `run.ps1` 完成依赖/JDK 准备，再编译并运行 `tests/` 下的 Java 测试。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1
```

测试包含：路径 `S`/`n`/`W n`/填充矩形、闭合路径去重、正常跨行选项、重复 B、六选项 A-F、逆序、缺号、超出单字母支持的标记、一个自制的五题型/跨行下划线 PDF，以及有本机真实样本时的 201 题/58 下划线/6 个跨行空集成回归。没有私有样本时，合成测试照常运行，集成回归明确打印 `SKIP`。

## 输出

`work/phase-01-revision-01/`（正式返工输出）或 `work/phase-01/`（首次交付）：

- `questions.json`：统一题目 JSON，含题型、原编号、题干、选项、答案、页码、待校对标记。选择题新增 `optionRawText`，每个选项带 `markerIndex`、`duplicate`、`supported`。
- `summary.json`：数量、编号缺口/重复、下划线匹配、待校对原因等汇总。
- `raw/text.txt`：按页重建的完整可见文本（含页脚，便于检查）。
- `raw/lines.tsv`：每行文字、边界框、基线、字号、是否页脚。
- `raw/glyphs.tsv`：每个字形的坐标、尺寸、字体、字号。
- `raw/underlines.tsv`：实际绘制的水平线段及其匹配到的行/字符/答案文本。
- `raw/run-command.txt`：本次实际运行命令。
- `render/page-*.png`：关键页 150 DPI 渲染图。

## 解析规则

- 文本层：`PDFTextStripper.processTextPosition` 逐字形读取；按基线聚类成行，按 x 排序；根据字距补空格。
- 下划线：继承 `PDFGraphicsStreamEngine` 暂存路径；只有后续 `S`/`f`/`B` 等实际描边或填充才提交候选，`n`/`W n` 丢弃，闭合/回折路径在同一路径内去重。只取水平、线宽不超过 2pt 的线段或薄矩形；把线段与同一页、基线附近的行做最近匹配，再按字形中心 x 坐标取覆盖文字。
- 填空：每个下划线组是一个空；若一条下划线到行尾、下一行同位置继续到左边距，则合并为跨行空，并标记 `cross_line_underline` 待人工校对。
- 选择/判断：只把括号内为 A-Z 字母或 √/× 的最后一处当作答案；题干中的普通括号不会被当成答案。
- 选项：扫描第一个 A 标记之后的全部 A-Z 单字母标记，保留重复/逆序/缺号；重复、逆序、缺号、`AA.` 这类超出单字母支持的标记都会进入待校对原因，`optionRawText` 保留原始选项区文本。正常六选项 A-F 会完整保留，不会把 F 吞进 E。
- 简答：按“参考答案”分割题干和参考文本。
- 页脚：基线在页面底部附近且形如 `N / M` 的行标记为页脚，解析时移除；原始 TSV 保留。
- 编号：保留原编号；裸编号行若后面紧跟同编号的完整题，则视为页末孤行跳过并记录异常，不生成假题。

## 与 Android 的关系

目标库仍是 `com.tom-roush:pdfbox-android:2.0.27.0`（Apache-2.0）。本工具使用其上游同版本 Apache PDFBox 2.0.27 的核心 API 做 JVM 验证；两者类名/方法基本同源，但 Android 侧需要：

- 把 `org.apache.pdfbox.*` 替换为 `com.tom_roush.pdfbox.*`；
- 在应用启动时调用 `PDFBoxResourceLoader.init(context)`；
- 预览可用 Android 的 `android.graphics.pdf.PdfRenderer`/View，或用 PdfBox-Android 自带的渲染器，而不是本工具使用的 JVM `PDFRenderer`。

Android 平台自身的 `PdfRenderer.Page.getTextContents()` 从 **API 35** 起提供文字内容（文本段 + 边界矩形）；API 36.1 又增加了页对象/注释相关方法。它们不等于本工具需要的逐字形坐标 + 内容流绘图线段 API，且在低版本系统不可用；因此本轮仍选 PdfBox-Android，但 Android 构建/实机仍未验证。详细证据和更正见 `docs/reports/01-dsh-revision-01.md`。
