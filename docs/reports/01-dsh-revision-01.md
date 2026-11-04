# 阶段 01 返工 01 报告

状态：**PHASE_01_REVISION_READY_FOR_REVIEW**  
日期：2026-11-02  
工作目录：`F:\New project`（已确认）  
依据：`docs/tasks/01-revision-01.md`、`docs/reports/01-codex-review.md`

> 本轮只处理阶段 01 返工。R1/R2 已用正式回归覆盖，R3 的 Android API/许可证描述已更正；真实样本重跑结果与旧交付在题目、答案、下划线和待校对状态上无差异。Android 构建/实机仍未完成，继续单独记录，未被当作解析缺陷已修复。

## 1. 本轮完成与未完成

### 已完成并实际验证

- R1：路径构造与实际绘制分离；`n`/`W n` 丢弃，只有 `S`/`f`/`B` 等绘制操作才提交下划线候选。
- R2：保留全部 A–Z 选项标记；重复、逆序、缺号、`AA.` 超出单字母支持均可见并进入待校对；新增 `optionRawText` 和选项标记元数据。
- 新增正式回归 `tools/pdf-probe/tests/pdfprobe/regression/PdfProbeRegression.java` 和运行入口 `tools/pdf-probe/run-tests.ps1`。
- 正式回归实际运行：**14 通过 / 0 失败，退出码 0**。
- 真实样本新输出在 `work/phase-01-revision-01/`：201 题、58 条下划线、6 个跨行空、6 道待校对题；数量/答案/下划线/待校对状态与旧结果一致。
- R3：按 API 35/36.1 更正 Android `PdfRenderer` 能力描述；按许可证兼容性而非“GitHub 禁止”更正 AGPL/商业许可描述。
- 旧交付 `work/phase-01/` 和 Codex 审查资料 `work/codex-review-01/` 均保留；审查资料哈希未变化。

### 尚未验证

- Android SDK/AGP/Gradle 构建、AAR 集成、`PDFBoxResourceLoader` 初始化。
- Android 16/RMX3800 真机运行、手机内存/耗时。
- 其他 PDF 的版式、下划线类型、旋转/多栏、图片题、扫描件/OCR。
- 六选项/重复选项在 UI 和数据库中的实际表现（本轮只保证解析 JSON 不丢失）。

### 阻碍（Android 环境，不是本轮解析缺陷）

本机仍没有 Android SDK、Gradle、adb、Android Studio 或可用设备；本轮未安装 SDK，也未进入阶段 02。因此不能宣称 Android 手机端已验收。最小环境需求见第 8 节。

## 2. R1：路径构造与绘制区分

### 原缺陷

旧实现 `lineTo` 一被调用就把候选写入 `underlines`；`n`（结束路径）没有描边也会留下候选。矩形 `re` 也在构造时直接计入。Codex 的复现结果是：

```text
painted_underlines=1
unpainted_underlines=1 expected=0
```

### 修复方式

`TextExtractor.PageGraphics` 改为先暂存当前路径：

- `moveTo`/`lineTo`/`curveTo`/`appendRectangle`/`closePath` 只构造或追加路径候选，不写结果。
- `strokePath()` 提交描边线段和薄矩形的描边候选。
- `fillPath()` 只提交薄矩形的填充候选；零面积线段不会被当作填充下划线。
- `fillAndStrokePath()` 同时提交两者。
- `endPath()` 清空路径；`clip()` 不提交，因此 `W n` 不产生下划线，`W S` 仍可提交实际描边。
- 只接受水平、长度至少 2pt、线宽不超过 2pt 的线段；薄矩形高不超过 2pt。
- 同一路径内的回折/闭合重复线段按坐标去重，避免 `m l l close S` 提交两次。
- 大矩形、普通路径、裁剪路径不会被误当作下划线。

### 正式回归覆盖

| 用例 | 期望 | 实际 |
|---|---:|---:|
| 文字下方的水平 `S` | 1 | 1 |
| 水平路径后 `n` | 0 | 0 |
| 矩形路径后 `n` | 0 | 0 |
| `W n` 裁剪路径 | 0 | 0 |
| 薄的填充矩形 `f` | 1 | 1 |
| 大的填充矩形 | 0 | 0 |
| 回折/闭合水平路径 | 1（去重） | 1 |

Codex 的同一复现程序在本轮代码上输出：

```text
painted_underlines=1
unpainted_underlines=0 expected=0
```

复现结果保存在 `work/phase-01-revision-01/codex-repro-after.txt`。

### 尚未覆盖的路径情形

本轮只按当前项目需要处理水平直线和薄矩形；尚未验证曲线路径、虚线、注释式下划线、白色/背景填充、表单外观流、旋转页面。报告不声称支持所有 PDF 路径。

## 3. R2：选项标记保留与异常可见

### 原缺陷

旧 `optionMarkers` 只接受期望顺序中的 A–E，重复/逆序/超范围标记被过滤后才做“连续”检查，结果：

- `A. first B. second B. duplicate C. third D. fourth` 被解析为 4 项，重复 B 吞进 B 文本，`needsReview=false`；
- 六选项 `A–F` 的 F 被吞进 E，仍无校对标记。

### 修复方式

- 扫描第一个 A 标记之后的全部 A–Z 单字母标记，不再先按期望序列过滤。
- 选项列表保留所有观测到的标记；重复键会以重复条目出现，并标记 `duplicate=true`、`markerIndex`。
- 选择题新增 `optionRawText`，完整保留第一个 A 标记后的原始选项区文本。
- 新增待校对原因：
  - `duplicate_option_marker`
  - `option_markers_out_of_order`
  - `option_marker_gap`
  - `option_keys_not_sequential`
  - `unsupported_option_marker`
- 明确支持 A–Z 单字母；`AA.` 这类多字母标记不静默吞并，保留在 `optionRawText` 并标记 `unsupported_option_marker`。
- 答案解析也扩展到 A–Z，六选项时可识别 F 答案；答案仍要求存在于选项键中。

### 正式回归覆盖

| 用例 | 结果 |
|---|---|
| 正常跨行选项 A–D | 4 项，D 合并跨行文字，无待校对 |
| 重复 B | 5 项保留 A/B/B/C/D，重复 B 文本独立，`needsReview=true` |
| 六选项 A–F | 6 项完整保留，E 不包含 F 文本 |
| 逆序 A/C/B/D | 4 项保留，标记逆序 |
| 缺号 A/B/D | 3 项保留，标记缺号 |
| `AA.` 超出单字母支持 | 标记 `unsupported_option_marker`，原文保留 |

正常样本的所有选择题仍是 A–E 且键唯一；新增的选项元数据没有改变题干、选项文字、答案或待校对状态。

### 行为变化与限制

- 发生重复标记时，`options` 数组可能出现重复 `key`；下游必须同时看 `needsReview`，不能假设“待校对题的选项键唯一”。
- 本轮正式支持单字母 A–Z；多字母或非字母标记只保留原文并标记，不试图猜测其编号。
- 六选项已被完整保留，但阶段 02 的 UI/数据库需要明确是否支持超过 5 个选项。
- 未做“选项文本语义是否正确”的判断；只保证标记不丢失、异常不静默。

## 4. R3：Android API 与许可证描述更正

### 4.1 Android `PdfRenderer` 能力

旧报告第 4 节把 Android 内置 `PdfRenderer` 概括为“只渲染 Bitmap，无文字提取 API”，这对当前 Android 版本不成立。准确说法如下：

| 能力 | API/版本 | 事实 | 对本项目的影响 |
|---|---|---|---|
| `PdfRenderer.Page.getTextContents()` | **API 35**（Android 15） | 返回 `PdfPageTextContent` 列表，包含文本段和边界矩形，按阅读顺序排列 | 可用于部分文字内容，但不能直接得到本探针使用的逐字形坐标 |
| `getImageContents()`、`searchText()` 等 | API 35 | 页面文本/图片内容与搜索能力 | 就公开文档看，未提供内容流下划线线段 API |
| `getPageObjects()`、`getPageAnnotations()`、`addPageObject` 等 | **API 36.1** | 新增页对象/注释相关方法 | 新版本能力，不等同于逐字坐标/下划线线段 API |
| `PDFTextStripper` + `PDFGraphicsStreamEngine` | PdfBox-Android/JVM | 可拿 `TextPosition` 坐标，并接 `lineTo`/`strokePath` | 本轮实际使用的路线 |

来源：

- [Android API 35 diff：PdfRenderer.Page 新增 getTextContents](https://developer.android.google.cn/sdk/api_diff/35/changes/android.graphics.pdf.PdfRenderer.Page)
- [Android API 36.1 diff：PdfRenderer.Page 新增页对象/注释方法](https://developer.android.google.cn/sdk/api_diff/36.1/changes/android.graphics.pdf.PdfRenderer.Page)
- [PdfRenderer.Page 官方文档](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page#getTextContents())
- [PdfPageTextContent 官方文档](https://developer.android.com/reference/android/graphics/pdf/content/PdfPageTextContent)

结论：

- 若未来最低 API 为 35+，可以评估用内置 `PdfRenderer` 获取文字段/边界；但填空需要下划线线段定位，本轮未找到公开的、与本探针 `PDFGraphicsStreamEngine` 等价的线段 API，因此仍不能单独满足本项目。
- 若最低 API 低于 35，内置文字 API 不可用，必须依赖库或自建解析。
- 项目最低 Android 版本尚未定；本阶段不把它定死。
- 因此**不换库**：继续选 PdfBox-Android，等阶段 02 做最小 Android 构建/实机验证后再决定是否保留。

### 4.2 许可证描述

旧报告把 iText/MuPDF 的 AGPL/商业许可概括为“不适合公开 GitHub”，不准确。GitHub 只是托管平台；是否可用取决于项目授权选择和义务履行：

- **PdfBox-Android / Apache PDFBox**：Apache-2.0。公开 GitHub 可用；需保留许可证/NOTICE，并注意 Apache 商标和第三方依赖声明。
- **Android `PdfRenderer`**：平台 API，不引入额外库许可证，但受 API 级别和设备实现限制。
- **PDF.js**：Apache-2.0；可离线打包，但仍需 WebView/JS 桥接。
- **iText 7 Core**：AGPL-3.0 或商业许可。公开仓库可以选择 AGPL 并履行源码/网络使用等义务；商业许可可避免这些义务。
- **MuPDF**：AGPL-3.0 或商业许可；Artifex 的许可页明确列出双许可和 AGPL 义务。

因此，iText/MuPDF 未入选的原因是**本项目授权策略、集成成本和维护权衡**，不是“GitHub 禁止 AGPL”。本轮不换库，只更正描述。

来源：

- [PdfBox-Android POM（Apache-2.0）](https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0.pom)
- [PDF.js LICENSE（Apache-2.0）](https://github.com/mozilla/pdf.js/blob/master/LICENSE)
- [iText Open-Source statement](https://itextpdf.com/open-source-statement)
- [Artifex licensing（MuPDF 双许可）](https://artifex.com/licensing)

### 4.3 维护与版本证据

- Maven Central metadata 的最新版本仍是 `2.0.27.0`，`lastUpdated=20230102065914`。
- GitHub release `v2.0.27.0` 发布于 2023-01-02，之后没有更新版本。
- 这不是“已停止维护”的绝对证明，但说明升级/修复风险需要保留；阶段 02 应把库封装在解析接口后，避免未来换库牵动 UI/数据库。

来源：[Maven metadata](https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/maven-metadata.xml)、[release v2.0.27.0](https://github.com/TomRoush/PdfBox-Android/releases/tag/v2.0.27.0)。

## 5. 正式回归

### 位置与运行

```text
tools/pdf-probe/tests/pdfprobe/regression/PdfProbeRegression.java
tools/pdf-probe/run-tests.ps1
```

实际命令：

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1
```

该入口会先用 `run.ps1` 完成 JDK/依赖/bootstrap，再编译并运行测试；测试失败时 `PdfProbeRegression` 调用 `System.exit(1)`，`run-tests.ps1` 透传非零退出码。

### 结果

```text
PASS: R1 painted horizontal stroke is one underline
PASS: R1 unpainted path is discarded
PASS: R1 unpainted rectangle is discarded
PASS: R1 clip-only path is discarded
PASS: R1 filled thin rectangle is one underline
PASS: R1 large filled rectangle is not an underline
PASS: R1 closed/backtracked path is not double-counted
PASS: R2 normal cross-line options remain four
PASS: R2 duplicate B marker is visible and flagged
PASS: R2 six options are parsed, not folded into E
PASS: R2 out-of-order markers are flagged
PASS: R2 missing marker is flagged
PASS: R2 unsupported multi-letter marker is visible and flagged
PASS: sample regression: 201 questions, 58 underlines, anomalies

PASS=14 FAIL=0
regression_exit=0
```

完整日志：`work/phase-01-revision-01/formal-regression.log`；UTF-8 摘要：`work/phase-01-revision-01/formal-regression-summary.txt`。

### Codex 复现结果（修复后）

`work/phase-01-revision-01/codex-repro-after.txt`：

```text
painted_underlines=1
unpainted_underlines=0 expected=0
duplicate_B_needsReview=true expected=true
option_A=first
option_B=second
option_B=duplicate
option_C=third
option_D=fourth
six_options_count=6 review=[]
```

## 6. 真实样本重跑与旧结果对比

### 输出

本次正式输出：

```text
work/phase-01-revision-01/
  questions.json
  summary.json
  raw/text.txt
  raw/lines.tsv
  raw/glyphs.tsv
  raw/underlines.tsv
  raw/run-command.txt
  render/page-20.png
  render/page-21.png
  render/page-22.png
  compare-with-phase-01.txt
  formal-regression.log
  formal-regression-summary.txt
  codex-repro-after.txt
```

旧交付 `work/phase-01/` 保留；Codex 审查资料 `work/codex-review-01/` 保留，未覆盖，哈希未变化。

### 数量与状态

| 项目 | 旧 `phase-01` | 新 `revision-01` |
|---|---:|---:|
| 总题数 | 201 | 201 |
| 单选 | 93 | 93 |
| 多选 | 44 | 44 |
| 判断 | 32 | 32 |
| 填空 | 25 | 25 |
| 简答 | 7 | 7 |
| 下划线总数 | 58 | 58 |
| 下划线匹配 | 58 | 58 |
| 跨行填空 | 6 | 6 |
| 待校对题 | 6 | 6 |
| `missing` | 92 | 92 |
| `orphan` | 填空 6 | 填空 6 |

### 语义差异

按 `id/type/sectionTitle/originalNumber/numberValue/stem/stemWithAnswer/options 的 key+text/answers/sourcePages/needsReview/reviewReasons` 比较：

```text
semantic_diffs = 0
```

原始提取文件逐字节一致：

```text
text.txt       identical=True
lines.tsv      identical=True
glyphs.tsv     identical=True
underlines.tsv identical=True
```

### 结构性变化（仅新增元数据）

- 选择题新增 `optionRawText`，完整保留原始选项区。
- 每个选项新增 `markerIndex`、`duplicate`、`supported`；样本中均为正常单字母、`duplicate=false`、`supported=true`。
- `questions.json` 体积由 246,777 增至 328,221 bytes，原因是新增原始选项/标记元数据；不是题量变化。
- 正常样本没有任何新的待校对原因：仍是 6 个 `cross_line_underline`。

对比明细见 `work/phase-01-revision-01/compare-with-phase-01.txt`。

## 7. 完成 / 实际验证 / 尚未验证 / 阻碍

### 已完成

- R1、R2 代码修复。
- R3 描述更正。
- 项目内正式回归与运行入口。
- 真实样本 revision-01 输出与旧结果对比。

### 实际验证

- 正式回归 14/0，退出码 0。
- Codex 的 R1/R2 复现程序在新代码上通过。
- 真实样本 201 题、58/58 下划线、6 个跨行空、6 道待校对。
- 新旧语义差异 0；原始文本/行/字形/下划线文件哈希一致。
- 审查资料未被修改。

### 尚未验证

- Android 构建/实机、AAR 资源初始化、Android 16 上的 PDFBox-Android 行为。
- 手机内存/耗时、大文件、其他 PDF 版式。
- 六选项/重复选项在 UI/数据库中的处理。
- 旋转页面、多栏、虚线/注释下划线、白色填充、表单外观流。

### 阻碍

- Android SDK、Gradle、adb、Android Studio、设备均缺失；本轮未安装、未进入阶段 02。
- 这是环境边界，不是 R1/R2 解析缺陷；不能据此宣称手机端已验收。

最小解阻环境（阶段 02 再做）：

1. JDK 17 或 21；
2. Android SDK command-line tools + platform 35/36 + build-tools + platform-tools；
3. Gradle/AGP 或 Android Studio；
4. Android 真机或模拟器；最终验收设备 RMX3800，Android 16，realme UI 7.0。

## 8. 产物与合规

- 正式返工报告：本文件。
- 状态：`docs/STATUS.md`，`PHASE_01_REVISION_READY_FOR_REVIEW`。
- 正式回归：`tools/pdf-probe/tests/pdfprobe/regression/PdfProbeRegression.java`、`tools/pdf-probe/run-tests.ps1`。
- 新样本输出：`work/phase-01-revision-01/`。
- 旧交付：`work/phase-01/` 保留。
- Codex 审查资料：`work/codex-review-01/` 保留。

边界：

- 未安装 Android SDK/Android Studio/协作插件。
- 未进入阶段 02，未创建付费服务。
- 未修改项目目录之外的文件或系统环境。
- 未提交、推送或发布。
- 真实样本和完整题库不进入公开提交。

## 9. 下一步

1. 等待 Codex 复查本轮 R1/R2/R3。
2. 若接受解析返工，阶段 02 再做最小 Android instrumentation：加载同一真实样本，验证 `PDFBoxResourceLoader.init`、201 题计数、58 条下划线、6 个跨行空和耗时/内存。
3. 在阶段 02 决定六选项的 UI/存储策略，以及 `duplicate_option_marker` 的校对交互。
4. 继续评估 PdfBox-Android 的维护风险，但不因本轮描述更正而立即换库。
