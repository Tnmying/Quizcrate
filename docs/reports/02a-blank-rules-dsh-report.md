# 阶段 02A 小改报告：跨行合并与顿号拆空

状态：**PHASE_02A_BLANK_RULES_READY_FOR_REVIEW**  
日期：2026-11-03  
工作目录：`F:\New project`

> 历史报告提示：本报告对应 `blank-rules` 版本；后续发现 `cleanText` 偏移在带排版空格的真实 PDF 上会导致错位，已由 `docs/reports/02a-blank-rules-revision-01-dsh-report.md` 修复并产出 `...-r1-debug.apk`。本文件保留为前一版记录。

> 结论：跨行合并改为“坐标 + 真实文字索引间隙”双重判断；明确的跨行答案自动合并，不再仅因跨行标红。原始答案区域识别完成后，只按答案内部中文顿号 `、` 生成学习子空，其他标点不拆；简答不转换。共享回归、Android 单元测试和 debug APK 均已实际跑通。新版 APK 尚未在真机上验证计数；用户反馈的是旧 APK 能运行/预览，不能当作新版真机已测。

## 1. 已完成、实际验证、尚未验证、阻碍

### 已完成

- 同一行按独立下划线生成原始填空区域；同行的多个下划线不会被合并。
- 跨行只在前一段延续到行尾、后一段从下一行开头继续，且两段在原文文字索引中连续时合并。
- 两段之间存在未划线 `、`、`，`、`和` 或其他正文时不合并；检查真实文字间隙，不只依赖坐标。
- 下划线覆盖范围内的标点不再在提取时修剪；原始答案区域保留原文标点。
- `crossLine` 只作为“发生跨行合并”的事实/诊断，不再自动触发 `needsReview`。
- 只有歧义/覆盖边界不确定/文字缺失或重叠等情况进入待校对；中文界面映射具体原因，不显示内部英文 code。
- 先完成跨行合并，再只按已识别填空答案内部的 `、` 拆学习子空；`、` 保留在题干，其他标点不拆。
- 连续/首尾 `、` 不生成空答案；保留完整原文并标记 `empty_enumeration_item` 待校对。
- 只对 `fill_blank` 执行；简答参考答案原样保留，不自动转换为填空。
- 新 APK 文件名、versionCode 2、同一应用标识/调试签名；旧 APK 保留。

### 实际验证

- 共享解析回归：**23 PASS / 0 FAIL**（含真实样本）。
- 无私有样本：**22 PASS / 0 FAIL + sample SKIP**。
- Android 本地 JVM 单元测试：**4 tests / 0 failures / 0 errors**。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**。
- 真实样本重新提取到 `work/02a-blank-rules/`：201 题，题数/题型未变；58 条物理下划线；52 个原始填空区域；52 个学习空；6 个跨行合并；待校对 0。
- 新 APK 已做版本、权限、签名、隐私扫描（见第 5 节）。

### 尚未验证

- 新版 APK 未安装到真机；没有新版真机的 201/52/58 计数证据。
- Android 上的 PdfBox-Android 资源加载、真实文件选择器、渲染、性能/内存仍未在本轮验证。
- 其他 PDF 版式、旋转/多栏、注释式/白色下划线、跨页合并仍未验证。
- 真机 UI 中文校对文案仅通过代码映射检查，未做截图级验证。

### 阻碍

- 没有可用授权设备：`adb devices -l` 为空；不安装 APK、不连接用户手机。
- 用户此前反馈旧 APK 可以运行并显示预览，但没有完整计数；本轮不据此宣称新版真机通过。

## 2. 规则实现

### 2.1 原始空与跨行合并

`QuestionParser.parseFill` 现在先按每条匹配下划线构造候选原始区域：

- 同一行的独立下划线始终是两个原始区域。
- 只有候选跨行对同时满足以下条件才合并：
  1. 前一段到该行行尾（3pt 容差）；
  2. 后一段从下一行行首开始（3pt 容差）；
  3. 下一行的行号紧接上一行；
  4. 两段在拼接后的原文中的文字索引严格连续：`next.start == last.sentenceEnd`。
- 如果 `next.start > last.sentenceEnd`，中间有未划线标点/正文，保持两个原始区域，不吞文字。
- 如果 `next.start < last.sentenceEnd`，文字范围重叠，保持分离并标记 `overlapping_cross_line_text`。
- 只有明确的 `EXACT` 合并设置 `crossLine=true`；它现在只是事实/UI 诊断，不再加入 `reviewReasons`。
- 提取器不再把下划线首尾标点当作可修剪内容；只去除空白。这样下划线内部的标点保留在原始答案区域。阶段 01 的样本没有下划线内标点，因此样本原始 `underlines.tsv` 没有变化。

### 2.2 学习子空

新增数据层：

- `BlankSpan`：`answer`（原始区域完整答案）、`crossLine`、`splitByEnumeration`、`learningBlanks`、物理 `segments`。
- `LearningBlank`：`answer`、`sentenceStart`、`sentenceEnd`。
- `Question.learningBlankCount` 与 `Question.answers`：学习空的扁平结果。

处理顺序：

1. 先完成原始区域识别与合法跨行合并；
2. 只在该原始答案字符串内部按 `、` 切分；
3. 每个非空片段生成一个学习空，并计算它在原句中的绝对范围；
4. 题干按学习空范围逐个替换为 `____`，`、` 原样保留；
5. 原始 `answer` 和 `segments` 仍保留，不把拆分出的子空伪装成新增物理下划线。

例子：

```text
原始下划线答案：有理想、敢担当、能吃苦、肯奋斗
题干：____、____、____、____
学习答案：["有理想", "敢担当", "能吃苦", "肯奋斗"]
```

歧义处理：

- `、甲、、乙、` 有首尾/连续顿号：不生成空答案；保留完整原文，生成一个学习空作为回退，并标记 `empty_enumeration_item`。
- 下划线内部只有逗号/分号/句号/冒号/英文逗号：不拆分，保持一个学习空；答案保留这些标点。
- 未划线正文中的 `、` 不参与拆分，不生成新空。
- 简答走 `parseShort`，不进入课堂空拆分；参考答案原样保留。

### 2.3 UI

- 填空卡片显示“原始空 N · 学习空 M”。
- 有跨行合并时显示中性文字“已自动合并跨行下划线”，不作为红色警告。
- `needsReview` 才显示红色“待校对”，原因通过 `reviewReasonLabel` 映射为中文；未知原因显示“需要人工核对”，不暴露内部英文 code。
- 六选项/重复选项的既有展示保持不变。

## 3. 测试

### 共享解析回归

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1
```

结果：

```text
PASS=23 FAIL=0
regression_exit=0
```

新增/覆盖：

- 一个原始空四个顿号项 → 四学习空、顺序正确、题干保留 `、`。
- 同一答案跨行 → 先合并，再拆四空；换行不改变空数。
- 两段之间存在未划线 `、`/`，`/`和` → 两原始区域、不吞文字、不误合并。
- 下划线内部逗号/分号 → 一个学习空。
- 未划线正文中的 `、` → 不产生新空。
- 简答参考答案 → 保持简答，不转填空。
- 首尾/连续顿号 → 无空答案、保留原文、准确待校对。
- 明确跨行 → 不因 `crossLine` 标红。
- 真正歧义（边界覆盖不确定）→ 仍保留待校对。

无私有样本：

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1 `
  -Sample local-inputs/does-not-exist.pdf
```

结果：`PASS=22 FAIL=0` + 明确 `SKIP sample regression`。

日志/摘要：

- `work/02a-blank-rules/shared-regression.log`
- `work/02a-blank-rules/shared-regression-summary.txt`
- `work/02a-blank-rules/shared-regression-no-sample.log`
- `work/02a-blank-rules/shared-regression-no-sample-summary.txt`

### Android 单元测试

`android/app/src/test/java/com/offlinequiz/app/QuestionParserTest.kt`：

- 五题型/跨行。
- 四顿号拆空。
- 跨行先合并再拆空，以及未划线间隙不合并。
- 六选项/重复 B。

实际结果：**4 tests, 0 failures, 0 errors**；XML 在 `work/02a-blank-rules/TEST-com.offlinequiz.app.QuestionParserTest.xml`。

### 构建

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File tools/android-build.ps1
```

实际任务：

```text
:app:testDebugUnitTest
:app:assembleDebug
```

结果：`BUILD SUCCESSFUL in 10s`。

## 4. 真实样本对比

新输出：`work/02a-blank-rules/`。旧输出：`work/phase-01-revision-01/`。

| 指标 | 旧 | 新 | 说明 |
|---|---:|---:|---|
| 总题数 | 201 | 201 | 未变 |
| 单选/多选/判断 | 93/44/32 | 93/44/32 | 未变 |
| 填空/简答 | 25/7 | 25/7 | 未变 |
| 物理下划线 | 58 | 58 | 提取层未变 |
| 下划线匹配 | 58 | 58 | 未变 |
| 原始填空区域 | 52 | 52 | 6 个跨行合并后 |
| 学习空 | 52 | 52 | 本样本答案内部没有 `、`，所以暂不增加 |
| 跨行合并事实 | 6 | 6 | 仍保留事实 |
| 跨行待校对 | 6 | 0 | 明确跨行不再红色警告 |
| 待校对题 | 6 | 0 | 全部是旧 `cross_line_underline` |
| 缺失编号 92 | 有 | 有 | 仍不补造 |
| 填空孤行 6 | 有 | 有 | 仍处理 |

额外比较：

- 题数、题型、题干、选项 key+text、答案、来源页的语义差异为 **0**。
- `text.txt`、`lines.tsv`、`glyphs.tsv`、`underlines.tsv` 逐字节一致。
- 新 `questions.json` 增加 `learningBlankCount`、`splitByEnumeration`、`learningBlanks` 等字段；旧字段保持兼容。
- 本样本修改后的 `withReview` 为 0。没有机械维持旧“6 个警告”；旧 6 个只是跨行事实，按新规则不是歧义。

对比明细：`work/02a-blank-rules/compare-with-phase-01-revision-01.txt`。

## 5. 新 APK

| 项目 | 值 |
|---|---|
| 文件 | `outputs/apk/offlinequiz-02a-blank-rules-debug.apk` |
| 大小 | 41,717,128 bytes |
| SHA-256 | `15EFCD40CA5217551BC998D2FF9EEE149EF990680219A66872F3523DC6903519` |
| versionCode / versionName | 2 / `0.2a-blank-rules` |
| package / min / target / compile | `com.offlinequiz.app` / 29 / 36 / 36 |
| 签名 | 与旧 APK 相同的 Android Debug 证书；`apksigner verify` 通过 |
| 权限 | 无 `INTERNET`/存储/相机权限；只有 AndroidX 自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| APK 隐私扫描 | 无 `sample.pdf`、`questions.json`、`local-inputs`、真实题干/答案字符串 |

旧 APK：

```text
outputs/apk/offlinequiz-02a-debug.apk
41,670,947 bytes
SHA-256 C7697701960F81F6C639CD838D1E8C76292660244164AB34D99736159921DD53
```

保留未覆盖。新 APK 使用相同 `applicationId` 和调试签名，versionCode 从 1 提高到 2，便于覆盖安装。

## 6. 真机与 Android 边界

- 用户已反馈旧 APK 在 RMX3800/Android 16 上可以运行并显示题目预览；但尚未提供完整 201/52/58 计数，因此只记为“旧版可运行/预览”，不是完整验收。
- 本轮没有可用连接设备：`adb devices -l` 为空；未安装新版 APK 到任何手机。
- 因此不声称新版 APK 的真机解析、计数、性能或中文校对 UI 已通过。
- 真机复核步骤已更新到 `docs/reports/02a-device-checklist.md`：预期 201 题、58 物理下划线、52 原始空/52 学习空、0 待校对、6 个跨行中性提示。

## 7. 未验证与限制

- 新版 APK 的真机安装/解析/计数/性能。
- PdfBox-Android 在 Android 16 上对真实样本的字体资源和路径行为。
- 其他 PDF 的行尾/行首判定、下划线内标点、旋转/多栏、跨页合并。
- 注释式下划线、白色/背景填充、虚线等非本轮支持范围。
- 六选项/重复选项的 UI 可读性只在旧版代码路径中，本轮未改变该展示。

## 8. 产物清单

- 新 APK：`outputs/apk/offlinequiz-02a-blank-rules-debug.apk`
- 旧 APK：`outputs/apk/offlinequiz-02a-debug.apk`（保留）
- 样本新输出：`work/02a-blank-rules/questions.json`、`summary.json`、raw、比较文件、测试日志
- 共享回归：`tools/pdf-probe/tests/pdfprobe/regression/PdfProbeRegression.java`
- Android 测试：`android/app/src/test/java/com/offlinequiz/app/QuestionParserTest.kt`
- 报告：本文件
- 状态：`docs/STATUS.md`

## 9. 下一步

1. 用户手动把新 APK 安装到真机，按 `docs/reports/02a-device-checklist.md` 核对 201/58/52/52/0 和跨行中性提示。
2. Codex 复查代码、回归、样本对比和 APK。
3. 若真机计数与桌面不一致，保留手机端诊断信息，针对 Android 资源/字体/路径 API 修正。
4. 不进入 02B；未提交/推送/发布。
