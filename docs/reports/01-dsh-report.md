# 阶段 01 交接报告：环境检查与安卓离线 PDF 提取可行性

状态：**PHASE_01_BLOCKED**  
日期：2026-11-02  
工作目录：`F:\New project`（已确认与 AGENTS.md 一致）

> 结论先说：真实样本的 JVM 提取已经跑通，201 道题的数量与基线一致，文字/字符坐标、58 条下划线和跨行填空都得到了可复现结果。但本机没有 Android SDK、Gradle、adb 或可用设备，**没有完成 Android 端构建/实机运行**，因此本阶段不能宣称“手机可行性已验收”；按任务要求标记为 BLOCKED，等待 Codex 决定补环境还是接受 JVM 证据并降级验收。

> 修订提示（2026-11-02）：本报告是首次交付的历史记录。第 4 节关于 Android `PdfRenderer` 无文字 API、以及 AGPL/商业许可证的概括性表述已被 `docs/reports/01-dsh-revision-01.md` 更正；当前有效结论以返工报告为准。

## 1. 完成情况

### 已完成并实际运行

- 检查了工作目录、Java/JDK、Node、Python、Gradle、Android SDK/adb 等环境。
- 用 Apache PDFBox 2.0.27 逐页面读取了 `local-inputs/sample.pdf` 的 14,065 个字形坐标，重建 572 行文本（其中 23 行为页脚）。
- 从内容流中读出并匹配了 58 条水平描边下划线（全部 0.48pt，集中在第 20–22 页），无未匹配线段。
- 实现了可重跑的 Java 探针 `tools/pdf-probe/`，生成统一题目 JSON。
- 实际提取出 **201 道题**：单选 93、多选 44、判断 32、填空 25、简答 7，与 PROJECT_BRIEF 的基线一致。
- 处理了编号 92 缺失、填空页末孤立 `6.`、跨行选项、多选多答案、判断符号、简答参考答案拆分、跨行下划线等案例。
- 连续运行两次到不同输出目录，除 `generatedAt` 外 JSON 完全一致。
- 生成了关键页渲染图、完整文本、字符坐标、下划线明细和汇总。

### 尚未验证

- Android 端编译、AAR 集成、资源初始化、Android 16 实机运行。
- 手机上的内存/耗时/大文件行为。
- 其他 PDF 的版式泛化能力；本报告只证明真实样本和当前规则。
- 扫描件/OCR、图片题、公式题（本版本来就不在范围）。
- 旋转页面、多栏排版、注释式下划线、填充矩形式下划线。

### 阻碍

- 本机没有 Android SDK、`ANDROID_HOME`/`ANDROID_SDK_ROOT`、Gradle、adb、Android Studio 或模拟器。任务禁止自动下载完整 SDK/Android Studio，故先完成 JVM 可行性验证并停止。
- 因此无法证明“手机端可运行”，只能说“选定库具备 Android 离线解析所需的文字位置和绘图线段 API，且同源 JVM 版本已在真实样本上跑通”。

## 2. 环境检查

只做了只读检查；没有修改系统环境变量、没有安装全局依赖、没有读取凭据或会话历史。

| 项目 | 实际结果 |
|---|---|
| 工作目录 | `F:\New project`；与约定一致 |
| Java | `C:\Program Files\JetBrains\PyCharm 2025.3.2.1\jbr\bin\java.exe`，OpenJDK/JBR `21.0.9` |
| javac | 同目录 `javac.exe`，`21.0.9` |
| JAVA_HOME | 未设置；`java`/`javac` 也不在 PATH 中 |
| Node | DSH 自带 `v24.21.0`（`C:\Users\Tnmying\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe`）；未用于最终探针 |
| Python | DSH 自带 `3.12.14`；仅用于辅助查看 JSON/图片。PATH 中的 `python.exe` 是 WindowsApps 存根，直接执行失败 |
| PowerShell | Windows PowerShell `5.1`；本机没有 `pwsh` 可执行文件 |
| Gradle | 未找到 |
| Android SDK / build-tools | 未找到；`ANDROID_HOME`、`ANDROID_SDK_ROOT` 均未设置 |
| adb / sdkmanager | 未找到 |
| Android Studio | 未安装（常见路径均不存在） |
| Git | 未找到；未执行任何提交或推送 |
| 网络 | 仅首次运行从 Maven Central 下载 PDFBox 依赖；下载成功 |

JDK 来自 PyCharm 自带的 JBR，不是独立安装的完整 JDK。它能编译和运行本探针，但阶段 02 若要用 Gradle/AGP，建议使用独立 JDK 17 或 21，并补充 Android SDK。具体最小环境见第 9 节。

## 3. 样本检查与坐标/下划线观察

### 基本事实

| 项目 | 值 |
|---|---|
| 文件 | `local-inputs/sample.pdf` |
| 大小 | 337,282 bytes |
| SHA-256 | `749AB284EA0C0A88EB7C49F1B8DD6DE4D76277BB49E3D86243923ACBEA70F795` |
| 页数 | 23 |
| 页面尺寸 | 595.30 × 841.90 pt，旋转 0 |
| PDF 版本 | 1.7 |
| 加密 | 否 |
| 文字层 | 有；Microsoft YaHei 子集字体，正文约 10pt，标题 18pt，页脚 9pt |
| 字形数 | 14,065 |
| 去空白字符数 | 13,959 |
| 重建行数 | 572（含 23 行页脚；正文行 549） |
| 重复字形 | 0（按文字+坐标去重后为 0） |
| 水平描边线段 | 58 条，线宽均 0.48pt，分布在第 20–22 页 |

### 文字顺序

按“页 → 基线 → x”重建。页脚位于页面底部，文字形如 `1 /23`、`11/ 23`，已识别 23 行并在题目解析中移除；原始 `text.txt`/TSV 保留它们，便于人工检查。

### 下划线

下划线是 PDF 内容流中的水平描边，不是从文本颜色或字符下划线属性猜出来的。样本中每条线段的 y 坐标约在文字基线下方 1.15pt：

```text
L 20 240.80 529.20 272.40 529.20 0.480
```

对应第 20 页填空第 1 题的“智能化”。`raw/underlines.tsv` 保留了每条的坐标、线宽、匹配行号、起止字形索引和匹配文本。

## 4. 候选解析路线与选择

### 候选对比

| 路线 | 许可证 | Android 离线 | 文字/字符位置 | 绘图线段/下划线 | 结论 |
|---|---|---|---|---|---|
| **PdfBox-Android 2.0.27.0**（TomRoush） | Apache-2.0 | AAR，minSdk 14，targetSdk 33 | `PDFTextStripper` + `TextPosition` | `PDFGraphicsStreamEngine` 可接 `lineTo`/`strokePath` | **选定**；能力最匹配，但版本老，需实机验证 |
| Android 框架 `PdfRenderer` | Android 平台 | 系统 API，完全离线 | 只渲染 Bitmap，无文字提取 API | 无矢量线段 API | 不能单独满足需求 |
| PDF.js（WebView） | Apache-2.0 | 可打包离线 | 文本内容/坐标可用 | 需要解析 operator list，桥接复杂 | 备选，不作为首选原生路线 |
| iText / MuPDF | AGPL 或商业 | 可离线 | 能力够 | 能力够 | 公开 GitHub 项目的许可证约束不合适；不选 |

### 选定路线

**PdfBox-Android `com.tom-roush:pdfbox-android:2.0.27.0`**：

- 许可证：Apache License 2.0（POM 明确列出）。
- 最新版本：Maven Central metadata 为 `2.0.27.0`，对应 GitHub release `v2.0.27.0`，发布于 2023-01-02；之后无新版本。可维护性是本路线的主要风险。
- AAR `AndroidManifest.xml`：`minSdkVersion=14`、`targetSdkVersion=33`；AAR 自带 `assets` 资源和 `PDFBoxResourceLoader`。
- AAR 的 `classes.jar` 中确实存在 `PDFTextStripper`、`PDFGraphicsStreamEngine`、`PDFBoxResourceLoader`。
- 其文本 API 与上游 PDFBox 2.0.27 同源；本探针用上游 PDFBox 2.0.27 验证真实样本，目的是先证明“字形坐标 + 矢量线段”路线本身可行，不冒充 Android 实机结果。
- Android 移植不是零改动：需要把 `org.apache.pdfbox.*` 改为 `com.tom_roush.pdfbox.*`，用 `PDFBoxResourceLoader.init(context)` 初始化资源，并把 `PDFGraphicsStreamEngine` 中的 `java.awt.geom.Point2D` 改成 Android 的 `PointF`/等价坐标类型。具体工作量应在阶段 02 做一个最小 instrumentation 测试来确认。

### 官方来源

- [PdfBox-Android v2.0.27.0 release](https://github.com/TomRoush/PdfBox-Android/releases/tag/v2.0.27.0)
- [PdfBox-Android Maven POM（Apache-2.0）](https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0.pom)
- [PdfBox-Android Maven metadata](https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/maven-metadata.xml)
- [Apache PDFBox 2.0 `PDFTextStripper` Javadoc](https://javadoc.io/doc/org.apache.pdfbox/pdfbox/2.0.27/org/apache/pdfbox/text/PDFTextStripper.html)
- [Apache PDFBox 2.0 `PDFGraphicsStreamEngine` Javadoc](https://javadoc.io/doc/org.apache.pdfbox/pdfbox/2.0.27/org/apache/pdfbox/contentstream/PDFGraphicsStreamEngine.html)
- [Android `PdfRenderer` 文档](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)

## 5. 可重跑程序与命令

代码位置：`tools/pdf-probe/`

```text
tools/pdf-probe/
  run.ps1                       # 查找 JDK、下载并校验依赖、编译、运行
  README.md                     # 使用说明与输出格式
  src/pdfprobe/
    PdfProbe.java               # CLI、输出 JSON/TSV/渲染
    TextExtractor.java          # 字形坐标、文本行、水平线段
    QuestionParser.java         # 题型/题目/答案/填空下划线解析
    Model.java                  # 数据模型
    Json.java                   # 轻量 JSON 序列化
```

实际运行命令：

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File tools\pdf-probe\run.ps1 `
  -InputFile local-inputs/sample.pdf `
  -OutDir work/phase-01
```

本机实际 JDK：

```text
C:\Program Files\JetBrains\PyCharm 2025.3.2.1\jbr\bin\java.exe
```

工具首次运行会自动下载并校验以下依赖（不安装到系统）：

| 依赖 | 版本 | 方式 |
|---|---|---|
| Apache PDFBox | 2.0.27 | `pdfbox-2.0.27.jar`，Maven Central |
| FontBox | 2.0.27 | `fontbox-2.0.27.jar`，Maven Central |
| Commons Logging | 1.2 | `commons-logging-1.2.jar`，Maven Central |

`run.ps1` 对三个 jar 做 SHA-256 校验；jar 和 `build/` 已被 `.gitignore` 忽略，不提交二进制。真实样本和全部提取结果位于 `work/`，同样被忽略。

### 重复性检查

对同一输入连续运行两次（`work/phase-01` 与 `work/phase-01-repro`），去掉 `generatedAt` 后 JSON 完全一致：

```text
canonical_sha256 = bbacd597623b5236ac405e0d6754707e486e564222dd8ae9b0f2580c602d5f08
```

代码中没有硬编码样本题干、答案或题量；题型标题、选项字母、答案括号、下划线匹配和编号异常都按结构规则解析。`work/phase-01-repro` 仅用于本次重复性检查。

## 6. 提取结果

### 数量与基线对照

| 题型 | 实际数量 | 原编号 | 说明 |
|---|---:|---|---|
| 单选 | 93 | 1–91, 93, 94 | 原样本没有 92；未补造 |
| 多选 | 44 | 1–44 | 有 4 选项和 5 选项两种 |
| 判断 | 32 | 1–32 | √ 18 题，× 14 题 |
| 填空 | 25 | 1–25 | 第 20 页末孤立 `6.` 不计题；下一页完整第 6 题计入 |
| 简答 | 7 | 1–7 | 7 题均有“参考答案”分割 |
| **总计** | **201** | | 与基线 201 一致 |

其他校验：

- 单选 93 题均为 4 个选项、1 个答案。
- 多选 44 题：27 题 4 个选项，17 题 5 个选项；答案 2、3、4、5 个字母的分布分别为 2、13、25、4 题。
- 137 道选择题（93+44）的选项键均从 A 连续到最后一个选项，没有空选项、重复键或答案字母不在选项中的情况。
- 判断题答案符号均可识别；没有空答案。
- 23 行页脚全部移除；未误删题目行。
- 2 条编号异常：单选缺 92；填空第 20 页孤立 6。
- 58 条下划线全部匹配；6 个跨行空，全部标记 `cross_line_underline` 待校对。
- 6 道待校对题全部是跨行填空：第 5、6、11、14、17、19 题；其余 195 题没有结构性校对标记。

### 关键案例

#### （1）单选编号 91 → 93 → 94，缺 92

解析结果保留原编号：

```text
single_choice-091  originalNumber=91
single_choice-092  originalNumber=93
single_choice-093  originalNumber=94
```

`summary.json` 记录：

```json
"single_choice": { "count": 93, "min": 1, "max": 94, "missing": [92], "duplicates": [] }
```

没有生成“第 92 题”。

#### （2）填空页末孤立 `6.`

第 20 页最后一行只有 `6.`，第 21 页开头是完整的 `6.二十届四中全会...`。解析器把前者记录为 `orphan_number` 并跳过，不生成空题；后者正常计入填空题第 6 题。最终填空仍为 25 题。

#### （3）跨行选项

单选第 9 题选项 D 在原文中被换行：

```text
D.
提出书面转正申请
```

输出仍为一条完整选项：

```text
D = 提出书面转正申请
```

同样处理了单选第 13、18、39、60、87、88 等题，以及多选第 6、16、26、29、34 等题的跨行选项。

#### （4）多选多答案

例如多选第 1 题：

```text
题干：党章规定的民主集中制基本原则中的“四个服从”包括（ ）。
答案：["A", "B", "D", "E"]
```

括号内的原始答案已从题干挖空，选项 A–E 分开保存；没有把答案留在题干里，也没有把多选误判成单选。

#### （5）判断题符号

判断第 1 题：

```text
原句：发展党员应坚持入党自愿原则和个别吸收原则，成熟一个，发展一个。（ √ ）
输出：stem = ...（ ）; answers = ["√"]
```

第 32 题输出 `["×"]`。共 18 个 √、14 个 ×。

#### （6）填空：原句 → 挖空题干 → 各空答案

以第 20 页第 1 题为例，真实下划线覆盖“智能化”“绿色化”“融合化”“先进制造业”：

```text
原句：党的二十届四中全会提出，坚持智能化、绿色化、融合化方向，构建以先进制造业为骨干的现代化产业体系。
题干：党的二十届四中全会提出，坚持____、____、____方向，构建以____为骨干的现代化产业体系。
答案：["智能化", "绿色化", "融合化", "先进制造业"]
```

每个答案都带有 `blanks[].segments[]`：页码、行号、线段 x0/x1/y 和匹配文字；不是按常识猜的。

第 4 题只有两个下划线，因此只输出两个空，尽管原句还有“执着专注”“追求卓越”等短语：

```text
题干：工匠精神的主要内容是执着专注、____、____、追求卓越。
答案：["精益求精", "一丝不苟"]
```

这正好说明程序没有用语义补全答案。

#### （7）跨行下划线

第 6 题的下划线从第 21 页第一行末尾延伸到第二行开头：

```text
原句：...到 2035 年基本实现社会主义现代化。
题干：...到 2035 年____。
答案：["基本实现社会主义现代化"]
线段：x=404.60..509.60（第一行）+ x=84.45..94.90（第二行）
```

合并规则是：上一条线段到行尾、下一条线段在同一页下一行且从左边距开始；合并后仍标记 `cross_line_underline`，供人工校对。

第 5 题更复杂，真实下划线覆盖三个人名+精神描述；第二空跨行合并为：

```text
["以张孤梅为代表的艰苦奋斗的创业精神",
 "以卢嘉锡为代表的严谨求实的治学精神",
 "以魏可镁为代表的勇于拼搏的奉献精神"]
```

题干为：

```text
福州大学“三种精神”分别是____；____；____。
```

第 19 题的 `["1921", "嘉兴南湖"]`、第 17 题的 `["立党为公", "为民造福", "科学决策", "真抓实干"]` 也使用了同样的跨行证据。

#### （8）简答拆分

7 道简答题均按“参考答案”分割。例如第 1 题：

```text
题干：根据 2026 年修订的《中国共产党发展党员工作细则》，简述发展党员工作的总要求、应始终放在首位的标准及坚持的两项原则。
参考答案：（1）总要求：... （2）始终把政治标准放在首位。 （3）坚持入党自愿原则和个别吸收原则，成熟一个，发展一个。
```

参考答案作为一个完整字段保存，并放入 `answers` 数组；没有把参考答案误并进题干。

### 与基线描述不完全一致的地方

任务基线提到样本存在“跨页题目”。本次逐页检查发现：

- 所有页面的首行要么是新题号，要么是新的题型标题；没有哪道题的题干/选项主体从上一页延续到下一页。
- 唯一与页边界有关的现象是填空题第 20 页末的孤立 `6.`，下一页重新开始完整的第 6 题，已按 `orphan_number` 处理。
- 因此本报告不声称“跨页题目已修复”；更准确的说法是：**没有观察到真正的跨页题干/选项；页末孤号已识别并排除**。如果 Codex 的基线另有所指，请给出具体页码/题号，下一轮可针对性复核。

## 7. 产物清单

本阶段实际生成（均在项目内）：

- `docs/reports/01-dsh-report.md`：本报告。
- `docs/STATUS.md`：已更新为等待验收/阻塞状态。
- `tools/pdf-probe/`：Java 探针、PowerShell 运行脚本、README。
- `work/phase-01/questions.json`：201 道统一题目 JSON。
- `work/phase-01/summary.json`：数量、编号、下划线、校对原因汇总。
- `work/phase-01/raw/text.txt`：按页重建的完整可见文本。
- `work/phase-01/raw/lines.tsv`：572 行文本及边界/基线/字号/页脚标记。
- `work/phase-01/raw/glyphs.tsv`：14,065 个字形的坐标和字体信息。
- `work/phase-01/raw/underlines.tsv`：58 条下划线的坐标和匹配结果。
- `work/phase-01/raw/run-command.txt`：本次实际运行命令。
- `work/phase-01/render/page-01.png`、`page-10.png`、`page-11.png`、`page-18.png`、`page-20.png`、`page-21.png`、`page-23.png`：关键页渲染图。

`work/phase-01-repro/` 是重复性检查的副本，不作为正式交付；`work/` 已被 `.gitignore` 忽略。

## 8. 实际验证 / 尚未验证 / 阻碍

### 对照 Codex 验收点

| 验收点 | 本阶段结果 |
|---|---|
| 文件真实存在；验证程序可重跑，结果不是抄写/硬编码 | 通过；连续两次运行结果一致，代码无样本题干/答案/题量硬编码 |
| 文字与下划线分离正确；跨行空和编号异常不产生假题 | 通过；58/58 下划线匹配，6 个跨行空标记待校对，孤立 6 跳过，92 缺失不补造 |
| 解析路线可用于安卓离线；仅桌面原型不能宣称通过手机可行性验收 | **部分/未通过**；API 与 AAR 能力证据成立，但未完成 Android 构建/实机，不能宣称通过 |
| 数量与各类型代表题对照原文；不确定内容保留可校对标记 | 通过；201 题与基线一致，6 道跨行填空标记 `needsReview` |
| 没有提前搭完整应用、安装协作框架、发布或修改项目外设置 | 通过；未进入阶段 02，未安装插件，未提交/推送/发布 |

### 实际验证

- 在 `F:\New project` 用 JBR 21.0.9 编译并运行探针，退出码 0。
- 真实样本 23 页、14,065 字形；201 道题全部生成。
- 五个题型数量与 201 基线完全一致。
- 58/58 下划线匹配，6 个跨行空被识别。
- 137 道选择题选项键/答案字母结构校验通过。
- 7 道简答题均分割出参考答案。
- 两次运行结果除时间戳外一致。
- 关键页渲染成功，人工抽查了下划线位置与题目文本。

### 尚未验证

- **Android 端**：未编译 AAR，未运行 instrumentation，未在 Android 16 / RMX3800 上实测。
- 未测手机 CPU/内存/耗电/大 PDF 性能。
- 未测 AAR 的 `PDFBoxResourceLoader` 资源初始化、Android 16 上的字体/图形兼容性。
- 未测其他 PDF 的编号体系、页脚样式、下划线类型、旋转/多栏/图片版面。
- 未做无障碍、UI、数据库、练习/统计（阶段 02+）。

### 阻碍与环境需求

当前阻碍不是代码或 PDFBox 解析失败，而是 Android 验证环境缺失：

- 没有 Android SDK platform/build-tools；
- 没有 `adb`；
- 没有 Gradle；
- 没有 Android Studio；
- 没有可用的模拟器或已确认连接的设备。

最小解阻环境（阶段 02 再做，不在本阶段安装）：

1. JDK 17 或 21（当前 JBR 21 可用于 JVM；正式 Android 构建建议独立 JDK）。
2. Android SDK command-line tools + platform（建议 API 35/36）+ build-tools + platform-tools。
3. Gradle/AGP（或 Android Studio）与 Kotlin/Compose 工程。
4. 一台 Android 设备或模拟器；最终验收设备为 RMX3800，Android 16，realme UI 7.0。

如果 Codex 接受“JVM 同源 API 验证 + 官方 AAR 能力证据”，则本阶段可以按“有明确残余风险”通过；如果要求真实手机可行性，则必须补上述环境后重跑 Android 最小测试。

## 9. 下一步最小建议（不进入阶段 02）

1. **先决定验收口径**：是否接受 JVM 证据，还是要求 Android 最小 instrumentation 测试。若后者，按第 8 节补最小环境。
2. 阶段 02 的第一个 Android 测试只做一件事：把同一份 `sample.pdf` 放入 `androidTest` assets，调用 PdfBox-Android 的 `PDFTextStripper` 和自定义 `PDFGraphicsStreamEngine`，在 RMX3800 上输出题干数、下划线数、6 个跨行空和耗时/峰值内存。
3. 把本探针的结构性断言搬过去：总数 201、各题型数量、58 条下划线、孤立 6 跳过、92 缺失但不补造、跨行空合并。
4. 继续评估 PdfBox-Android 的维护风险（2023 年后无新版本）；在阶段 02 设计解析接口时隔离库依赖，避免以后换库牵动 UI/数据库。
5. 补充正式许可证/NOTICE 文件（Apache-2.0），并在公开仓库中排除真实样本和完整题库。

## 10. 合规与边界

- 未安装多智能体插件或协作桥；未启动子智能体团队。
- 未修改 DSH、Codex 设置或凭据。
- 未创建付费服务。
- 未修改项目目录之外的文件；未修改系统环境变量。
- 未自动提交、推送 Git 或发布 GitHub/APK。
- 未搭建完整 Android UI，未进入阶段 02。
- 真实样本、完整提取文本和题库均在 `work/` 或 `local-inputs/`，不进入公开提交；公开示例仍应使用自制题。
