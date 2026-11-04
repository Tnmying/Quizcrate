# 阶段 02A 返工 01 报告：顿号拆空排版空格映射

状态：**PHASE_02A_BLANK_RULES_R1_READY_FOR_REVIEW**  
日期：2026-11-03  
工作目录：`F:\New project`

> 结论：Codex 复现的 `甲、 乙、丙` 错位已修复。学习子空不再使用 `cleanText` 的偏移；改为从原始句子区间取原文、在原文上定位子空，并对答案单独 trim。复现输出为 `____、____、____`，答案 `[甲, 乙, 丙]`，无待校对、无答案泄露。新版 r1 APK 已构建；新版真机仍未验证。

## 1. 任务与根因

Codex 复现：

```text
original=甲、 乙、丙
stem=____、____乙____丙
answers=[甲, 乙, 丙]
review=[blank_text_mismatch]
answer_characters_leaked=true expected=false
```

根因：

- `TextExtractor`/`AndroidPdfExtractor` 重建 `line.text` 时会根据字形间距插入空格；
- `underline.cleanText` 只拼接字形，不包含这些排版空格；
- 旧 `buildLearningBlanks` 把 `cleanText`（如 `甲、乙、丙`）的切分偏移直接加到原句 `group.sentenceStart`（原句 `甲、 乙、丙`）上，位置错开；
- `blank_text_mismatch` 警告已经出现，但仍继续按错误偏移替换，导致题干泄露部分答案。

## 2. 修复方式

### 2.1 原文映射

新增 `BlankSpan.originalAnswer`：

```text
originalAnswer = sentence.substring(blank.sentenceStart, blank.sentenceEnd)
```

- 原始区域/跨行合并仍由 `sentenceStart/End` 和字形线段决定；这些索引来自原文 `sentence.text`。
- 学习子空在 `originalAnswer` 上按 `、` 切分，得到的是原文字符索引；
- 每个 `LearningBlank.sentenceStart/End` 使用原文范围，不用 `cleanText` 索引；
- 每个答案单独 `trim()`，只去除排版空白，不删除原文所有空格。

### 2.2 安全回退

- 比较 `originalAnswer` 与 `cleanText` 时只忽略排版空格（ASCII 空格/制表符）。
- 如果除了排版空格以外仍不一致（文字/标点缺失或重叠），不再强行拆分：
  - 保留完整原始范围作为一个学习空；
  - 标记 `blank_text_mismatch`；
  - `splitByEnumeration=false`；
  - 不输出错位子空。
- 连续/首尾 `、` 仍按原规则保留完整原文、回退一个空并标记 `empty_enumeration_item`。
- 未划线正文、跨行间隙、简答不转换等已确认规则未改。

### 2.3 示例

输入原文：

```text
甲、 乙、丙
```

输出：

```text
题干：____、____、____
答案：[甲, 乙, 丙]
原始答案：甲、 乙、丙
物理原始区域：1
学习空：3
待校对：无
```

`、` 后排版空格被包含在对应学习空的原始范围中，替换时不泄露答案；顿号保留。

## 3. 测试

### 3.1 Codex 复现

在同一 `ReviewSpacing.java` 和自制 PDF 内容流上重跑：

```text
original=甲、 乙、丙
stem=____、____、____
answers=[甲, 乙, 丙]
review=[]
answer_characters_leaked=false expected=false
exit=0
```

结果文件：`work/02a-blank-rules/r1/codex-spacing-result-after.txt`。

### 3.2 新增真实 PDF 端到端回归

`PdfProbeRegression.java` 新增 `checkSpacingPdf`，实际生成 PDF 内容流，再走 `TextExtractor + QuestionParser`，不是手工指定题目结果：

- 普通顿号后有排版空格：`甲、 乙、丙`
- 顿号前后都有空格：`甲 、 乙 、丙`
- 首尾排版空白：`1. 甲、 乙、丙 `
- 重复排版空白：`甲、  乙、丙`
- 跨行带开头空格：`甲、 乙、` + ` 丙`
- 无空格对照：`甲、乙、丙`
- 不可靠文字映射的安全回退（人为改错 `cleanText`）

每组断言：1 个原始区域、3 个学习空、完整题干 `____、____、____`、顿号保留、答案顺序正确、无甲/乙/丙泄露、子空范围映射回原文后可 trim 为对应答案。

共享回归实际结果：

```text
PASS=30 FAIL=0
regression_exit=0
```

无私有样本：

```text
PASS=29 FAIL=0
SKIP sample regression: file not found
regression_exit=0
```

日志：

- `work/02a-blank-rules/r1/shared-regression.log`
- `work/02a-blank-rules/r1/shared-regression-summary.txt`
- `work/02a-blank-rules/r1/shared-regression-no-sample.log`
- `work/02a-blank-rules/r1/shared-regression-no-sample-summary.txt`

### 3.3 Android 单元测试与构建

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File tools/android-build.ps1
```

实际结果：

- `:app:testDebugUnitTest`：4 tests / 0 failures / 0 errors
- `:app:assembleDebug`：BUILD SUCCESSFUL in 10s
- 共享解析逻辑与 Android 使用同一 `QuestionParser`；Android 侧仍通过 `stageCoreSources` 复制纯逻辑源码，避免桌面 PDFBox 进入 APK。

## 4. 真实样本复核

新输出：`work/02a-blank-rules/`（已用修复后代码重新提取）  
旧输出：`work/phase-01-revision-01/`

| 指标 | 旧 | 新 |
|---|---:|---:|
| 总题数 | 201 | 201 |
| 单选/多选/判断 | 93/44/32 | 93/44/32 |
| 填空/简答 | 25/7 | 25/7 |
| 物理下划线 | 58 | 58 |
| 匹配下划线 | 58 | 58 |
| 原始填空区域 | 52 | 52 |
| 学习空 | 52 | 52 |
| 跨行合并 | 6 | 6 |
| 待校对 | 6 | 0 |

- 题数、题型、题干、选项 key+text、答案、来源页的语义差异：**0**。
- `text.txt`、`lines.tsv`、`glyphs.tsv`、`underlines.tsv` 逐字节一致。
- 旧版 6 个 `cross_line_underline` 是明确跨行事实，不是新规则下的歧义，故新版待校对为 0；没有机械维持“6 个警告”的旧断言。
- 本样本答案内部没有 `、`，所以 52 个原始区域仍为 52 个学习空；新增的 6 个带排版空格 PDF 回归覆盖了拆空位置。

对比文件：`work/02a-blank-rules/compare-with-phase-01-revision-01.txt`。

## 5. 新 APK

| 项目 | 值 |
|---|---|
| 文件 | `outputs/apk/offlinequiz-02a-blank-rules-r1-debug.apk` |
| 大小 | 41,717,592 bytes |
| SHA-256 | `34CB8FA6224EEB6B22D758F8D03F578EBCEF6E16F5930176EC4D81E9D1DE6F56` |
| versionCode / versionName | 3 / `0.2a-blank-rules-r1` |
| package / min / target / compile | `com.offlinequiz.app` / 29 / 36 / 36 |
| 签名 | 与旧版相同的 Android Debug 证书；`apksigner verify` 通过 |
| 权限 | 无 `INTERNET`/存储/相机权限；只有 AndroidX 自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| 隐私扫描 | 无 `sample.pdf`、`questions.json`、`local-inputs`、真实题干/答案字符串 |

保留的旧 APK：

```text
outputs/apk/offlinequiz-02a-debug.apk
  SHA-256 C7697701960F81F6C639CD838D1E8C76292660244164AB34D99736159921DD53

outputs/apk/offlinequiz-02a-blank-rules-debug.apk
  SHA-256 15EFCD40CA5217551BC998D2FF9EEE149EF990680219A66872F3523DC6903519
```

新 APK 使用同一 `applicationId` 和调试签名，versionCode 从 2 提高到 3，便于覆盖安装。

## 6. 真机与未验证点

- 用户反馈旧版 APK 在 RMX3800/Android 16 上可以运行和预览，但没有完整计数证据；本轮不据此宣称新版已真机通过。
- 当前没有可用连接设备：`adb devices -l` 为空；未安装 r1 APK。
- 新版 r1 APK 的真机文件选择、PdfBox-Android 资源、实际 201/58/52 计数、性能/内存和中文校对 UI 均未验证。
- 其他 PDF 版式、跨页下划线、旋转/多栏、注释式/白色下划线仍未验证。

真机检查步骤已更新到 `docs/reports/02a-device-checklist.md`。没有新版真机数据前，不声称新版真机已通过。

## 7. 产物与边界

- 共享解析修复：`tools/pdf-probe/src/pdfprobe/QuestionParser.java`、`Model.java`
- Android 侧共享规则：通过 `stageCoreSources` 复用同一纯逻辑
- 共享回归：`tools/pdf-probe/tests/pdfprobe/regression/PdfProbeRegression.java`
- Android 单元测试：`android/app/src/test/java/com/offlinequiz/app/QuestionParserTest.kt`
- 新 APK：`outputs/apk/offlinequiz-02a-blank-rules-r1-debug.apk`
- 样本/测试产物：`work/02a-blank-rules/r1/`
- 审查资料：`work/02a-blank-rules/ReviewSpacing.java`、`review-spacing.pdf`、`codex-spacing-result-utf8.txt` 保留；复现结果另存为 `codex-spacing-result-after.txt`

边界：未进入 02B，未安装新 SDK/插件，未修改系统设置，未提交/推送/发布。

## 8. 下一步

1. 用户手动安装 r1 APK，核对 `甲、 乙、丙` 类答案和真实样本 201/58/52/52/0。
2. Codex 复查本轮映射修复、真实 PDF 回归和 APK。
3. 若真机结果不一致，保留手机诊断信息和对应 PDF 版式，再针对性修正。
