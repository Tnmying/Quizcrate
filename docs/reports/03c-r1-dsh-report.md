# 阶段 03C 返工 01 报告：数值标点与长文预处理边界

日期：2026-11-03。执行者：DSH。状态：`PHASE_03C_R1_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，19 个仪器测试仅编译未执行，真机未测。

## 结论

已修复 03C 验收的两项问题：

1. **数值标点**：`.` `:` `,` 在两侧紧邻数字时保留为数值分隔符（全角等价形式先折叠），`1.5`≠`15`、`1:2`≠`12`、`1,000`≠`1000`；普通句末标点仍删除。Codex 独立数值测试原样复跑由失败转为通过（十进制 71%、比例 62%，不再是 100%）。
2. **长文预处理边界**：预处理也加上限——每侧最多扫描 8000 个原始码点、最多保留 2000 个有效码点；不再先完整规范化再截断。超限时 `truncated=true` 并由界面明确提示，不静默截断、不阻止自评/保存/跳过。

同时修正了 03C 报告与真机清单中“展开后修改输入”的错误步骤：输入框在展开后按既有行为锁定，测试另一段文字需在下一题/新一轮展开前输入；未改锁定行为。

正式回归 **185 tests / 0 failures**（27 类），Codex 数值独立测试 2/0、既有冲突/取消独立测试各 1/0，解析回归 30/0，强制构建 79 任务全部执行。

## 1. 数值标点

### 根因

`ShortAnswerTextMatch` 的 `PUNCTUATION` 无条件包含 `.` 和 `:`；`normalizeToCodePoints` 不检查上下文直接删除，导致“1.5”→“15”、“1:2”→“12”，与用户输入的“15”/“12”完全一致（score=1.0/100%）。这是预处理丢失信息，不是 n-gram 的语义局限。

### 修复

算法版本升至 `dice-char23-v2`（`model/ShortAnswerTextMatch.kt`）：

- 规范化时对 `.` `:` `,` 做**数值上下文判断**：仅当它们在同一个规范化前缀内**直接相邻两侧都是数字**时保留；否则仍按普通标点删除。
- 全角 `．` `：` `，` 先按既有规则折叠为半角，再判断，因此全角与半角等价。
- 普通句读不受影响：句末 `1.`、标签后的 `答案：1.5`（冒号前不是数字）仍删除冒号/句点；`甲，乙。丙` 仍等价于 `甲乙丙`。
- 数字之间的逗号也保留（`1,000`≠`1000`），宁可保守保留，不臆测是列表还是千分位。
- 不引入完整数学解析器；`1.50` 与 `1.5` 字符不同仍会不同分，`1,5`（小数逗号）与 `1.5` 也因分隔符不同而不同。

### 实际验证

Codex 原独立测试 `work/codex-review-03c/tests/ShortAnswerNumericReviewTest.kt` **原样复跑，未改断言**：

```text
REVIEW decimal value: percent=71 score=0.7076923076923077
REVIEW ratio value: percent=62 score=0.6190476190476191
tests=2 failures=0 errors=0
```

正式新增/更新算法回归：

- `numericSeparatorsBetweenDigitsArePreserved`：`1.5`≠`15`、`1:2`≠`12`，相同数值仍 100%。
- `fullWidthNumericSeparatorsAreEquivalent`：`1．5`≡`1.5`、`1：2`≡`1:2`、`1，000`≡`1,000`。
- `ordinarySentencePunctuationIsStillRemoved`：`甲，乙。丙`≡`甲乙丙`、`答案是1.`≡`答案是1`、`答案：1.5`→`答案1.5`。
- `decimalAndThousandsSeparatorsAreNotCollapsed`：`0.5`≠`05`、`1,000`≠`1000`。
- ViewModel 集成新增 `numericSeparatorDifferenceReachesTheViewModel`：`1.5` vs `15` 在真实练习状态中不再是 100%。

## 2. 长文预处理边界

### 根因

原实现先对两侧完整调用 `normalizeToCodePoints`（构建全长 `StringBuilder`、`lowercase` 副本、`ArrayList<Int>`、`IntArray`），之后才 `copyOf(2000)`。2000 上限只限制后续 gram 计数，预处理本身仍在主线程完整分配和遍历。

### 修复

`match` 改为有界预处理：

- 每侧最多扫描 **8000 个原始码点**（`MAX_SCANNED_CODE_POINTS`）做折叠/小写，循环到上限即停止，不遍历全文。
- 最多保留 **2000 个有效码点**（`MAX_CODE_POINTS`）参与比较；达到上限即停止过滤。
- 预处理输出、内存与主线程工作量都有明确上限；不再存在“完整规范化后再截断”的路径。
- 任一上限被触发时 `truncated=true`；**不可用空态也携带该标记**，界面同时显示原因与“文本较长，仅检查了开头部分（最多 2000 个有效字符）”。
- 同步计算仍保留（8000 码点量级很小），因此没有异步迟到结果覆盖切题/反馈的竞态；自评/保存/跳过完全不受影响。
- 未改写原输入；超长不静默截断。

### 正式回归

- `longTextIsCappedWithAnExplicitFlag`（既有）：2500 字符同文 → `truncated=true`、100%。
- `punctuationOnlyLongTextReportsTruncatedEmptyState`：100 个标点 → 规范化空态且 `truncated=false`；10000 个标点 → 规范化空态且 `truncated=true`（只检查了开头）。
- `differencesAfterTheScannedPrefixAreNotSeenButFlagged`：9000 个“甲” vs 前 8000“甲”+后 1000“乙” → `truncated=true`、分数 100%，**固定“前缀后差异不可见”的已知边界**。
- `lowercaseExpansionAtTheBoundaryIsHandledWithoutFailure`：`İ` 重复 4000 次（小写展开为双码点）不崩溃、被截断、同文 100%。
- `astralCodePointsStayIntactAndTheScanBoundIsBounded`：保留区内代理对不拆；9000+ 代理对的长文有界、无崩溃、截断标记。
- 未做脆弱的固定毫秒性能断言。

## 3. 报告/真机说明更正

- `docs/design/short-answer-match.md`：算法版本 v2、数值标点规则、8000/2000 预处理边界、空格千分位与前缀差异局限、展开后输入锁定说明。
- `docs/reports/03c-dsh-report.md`：删除“展开后修改输入”的错误步骤，改为“展开后输入框锁定；返回/下一题/新一轮后在展开前输入另一段文字”。
- `docs/reports/03c-device-checklist.md`：同步更正，并注明 R1 起验收包为 `offlinequiz-03c-r1-debug.apk`。
- 未改变展开后锁定输入的行为。

## 实际验证（本机执行）

环境：`work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。

### 正式 Android JVM

`:app:testDebugUnitTest` → **27 个测试类，185 tests / 0 failures / 0 errors / 0 skipped**（03C 基线 176 + 新增 9：算法 8、ViewModel 集成 1）。

- `ShortAnswerTextMatchTest`：20 项（原 12 + 数值/全角/普通句读/千分位/长标点/前缀差异/小写展开/代理对 8）。
- `PracticeViewModelShortAnswerMatchTest`：10 项（原 9 + 数值区分 1）。
- `ShortAnswerMatchEvaluationTest`：1 项，评估表数字与 v1 相同（样例不含数值分隔符），限制断言仍通过。

结果 XML 副本：`work/03c-r1/formal-results/`（27 个）；日志：`work/03c-r1/formal-tests-final.log`、`work/03c-r1-full-build.log`。

### Codex 独立测试（原样，未改断言）

使用 `work/codex-review-03c/reviewer.init.gradle` 一次加入三份独立测试：

| 测试 | 结果 | 输出 |
| --- | --- | --- |
| `ShortAnswerNumericReviewTest`（2 项） | **2/0** | decimal 71%、ratio 62%，均 < 100% |
| `WrongbookConflictReviewTest`（1 项） | **1/0** | `REVIEW pending lookup: existing=active new=false` |
| `WrongbookCancelReviewTest`（1 项） | **1/0** | `REVIEW canceled confirmation: new=false started=null` |

日志 `work/03c-r1/codex-review-tests.log`，结果 XML 在 `work/03c-r1/`。未修改独立测试、init 脚本或断言。

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**，退出码 0，含真实样本 201 题；解析/拆空规则未改。

### 构建与仪器测试

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79 个 actionable tasks 全部执行**。

`:app:assembleDebugAndroidTest` 成功；仍为 **19 个**仪器测试。`adb devices` 为空，全部**未执行**；本阶段无 schema/迁移变化。

### APK

- 路径：`outputs/apk/offlinequiz-03c-r1-debug.apk`
- 大小：**43,608,861** 字节
- SHA-256：**`69956FFEE3BEF9413528488AF192161760D60AA4A9FFAABC14FA3B94C688D4A6`**
- applicationId：`com.offlinequiz.app`（未变）
- versionCode：**13**（> 12）；versionName：`0.3c-r1-short-answer`
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`，与 03C/03B R3 一致；`apksigner verify` 退出码 0。
- 权限：无 INTERNET/存储/相机；仅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
- 内容：243 个条目、0 个 `.pdf`；未检出 `sample.pdf`、`local-inputs`、`New project`、`C:\Users`。
- 旧 APK 全部保留且大小/哈希未变：03C（43,608,857 / `999B794C…`）、03B R3（43,592,481 / `85676186…`）、03B R2、03B R1、03B、03A R1、03A、02B R1、02B、02A 系列。

## 尚未验证（无设备）

- 19 个仪器测试未执行：真实 Room 事务/迁移、持久化未做设备实测。
- 真机：数值标点（1.5 vs 15、1:2 vs 12、全角）在练习/反馈中的展示；超长文提示与滚动；旋转/强杀恢复；展开后输入锁定的实际手感。
- 长文性能只有“有界代码路径”与 JVM 边界测试，未做手机卡顿/OOM 实测；未做固定毫秒断言。
- Compose UI 无自动化截图/点击测试；JVM 覆盖算法与 ViewModel 状态，UI 为源码接线核查。
- 深色/无障碍、低存储写入失败；仅 debug 构建，无 release 签名。

## 最短真机步骤

1. 覆盖安装：`adb install -r "F:\New project\outputs\apk\offlinequiz-03c-r1-debug.apk"`；确认 versionCode 13、同一调试签名，旧题库/练习数据仍在。
2. 进入含简答题的练习（或错题重练中的简答），在展开前输入“温度为15摄氏度”，参考答案为“温度为1.5摄氏度”。
   - 展开后期望：文字匹配度明显低于 100%（独立数值复现为 71%），并显示固定免责文案。
   - 同样测试“比例为12”对“比例为1:2”（约 62%）、“0.5”对“05”、“1,000”对“1000”。
3. 全角等价：参考答案“1.5”，输入“1．5”；参考答案“1:2”，输入“1：2”。
   - 期望：分别仍为 100%（全角分隔符与半角等价）。
4. 普通句读：参考答案“甲乙丙”，输入“甲，乙。丙” → 仍 100%；“答案是1.”与“答案是1” → 仍 100%。
5. 清空输入后展开：显示“尚未输入文字…”，不是 0%；自评/跳过仍可用。
6. 选择“记得/没记住”：反馈显示相同匹配度，自评单独统计；输入后跳过不显示匹配。
7. **展开后输入框应保持锁定**；要比较另一段文字，请返回/下一题/新一轮后在展开前输入（不要按旧报告尝试在展开态修改）。
8. 超长文本（可造 9000 字自制题库）：展开后应显示“文本较长，仅比较开头部分（最多 2000 个有效字符）”；不崩溃、不阻止自评/保存/跳过。
9. 先在会话中展开看到分数，再回题库改该题参考答案并重进旧会话：仍按旧快照比较。
10. 有设备后运行 19 项仪器测试：`gradlew :app:connectedDebugAndroidTest`（环境变量见 `docs/reports/03c-device-checklist.md`）。

## 变更文件

- `android/app/src/main/java/com/offlinequiz/app/model/ShortAnswerTextMatch.kt`：v2 数值上下文标点、8000/2000 有界预处理、不可用结果携带 truncated。
- `android/app/src/main/java/com/offlinequiz/app/ui/PracticeScreen.kt`：截断提示文案；不可用空态也显示范围提示。
- `android/app/src/test/java/com/offlinequiz/app/ShortAnswerTextMatchTest.kt`：新增 8 项数值/长文/Unicode 回归。
- `android/app/src/test/java/com/offlinequiz/app/PracticeViewModelShortAnswerMatchTest.kt`：新增数值区分集成回归。
- `android/app/build.gradle.kts`：versionCode 13、versionName。
- `docs/design/short-answer-match.md`：v2 规则与边界。
- `docs/reports/03c-dsh-report.md`、`docs/reports/03c-device-checklist.md`：修正展开后修改输入的错误步骤。
- `docs/STATUS.md`、`docs/DSH_HANDOFF.md`：状态与交接。

未改：Room schema/迁移、判题、自评/幂等/次数/错题/版本口径、历史页、PDF 解析与拆空规则、Codex 验收测试/任务/记录。

## 阻碍与边界

- 无实现阻碍。唯一限制仍是无安卓设备/模拟器；未安装 Android Studio、完整 SDK 或模拟器镜像，未改系统设置。
- 未提交、未推送、未发布；未覆盖 Codex 的任务说明与验收记录；未进入备份或发布。
- 完成本返工并写报告后停止，等待用户手动通知 Codex 验收。
