# 阶段 03C 报告：简答题离线文字匹配辅助

日期：2026-11-03。执行者：DSH。状态：`PHASE_03C_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，19 个仪器测试仅编译未执行，真机未测。

## 结论

已完成简答题的纯离线文字匹配辅助：字符 2-gram/3-gram 多重集 Dice，只在展开参考答案后、且仅作为“文字匹配度”展示；不设判对阈值，不影响自评、自动正确率、错题资格或掌握状态。

- 纯算法独立于 Compose/数据库，无网络、AI、词典或新依赖；算法版本 `dice-char23-v1`。
- 规范化保守：全半角/大小写统一、去空白与明确列出的普通排版标点，保留否定词、数字、条件与数学/比较符号（`+ - % < > =` 等），不删停用字、不引入分词。
- 空输入、参考答案缺失/为空、规范化后为空都有中文空态；不会把两个空答案显示 100%，也不会把“心中作答”显示 0 分。
- 基于当前练习的固定题目快照 `referenceAnswer`；自评反馈用记录的用户答案重算并显示同一结果；切题/恢复/改题不串用旧分数。
- 展开前不显示分数；计算放在 ViewModel 状态变化处，不在 Compose 每次重绘时执行。

评估表见 `docs/design/short-answer-match.md`（含实际分数与局限）；正式回归 **176 tests / 0 failures**（27 类），两份 Codex 独立测试原样复跑通过，解析回归 30/0，强制构建 79 任务全部执行。

## 用户体验与记录边界

- 简答仍可手动输入或心中作答；原“展开参考答案（心中作答）”“记得/没记住”“跳过”流程不变。
- 展开后：
  - 有输入 → 显示“文字匹配度 N%”，紧邻固定文案“仅比较文字重合，不代表答案正确；请自行判断”。
  - 无输入 → “尚未输入文字，无法比较文字重合；仍可直接自评或跳过。”
  - 参考答案为空/缺失 → “参考答案为空或缺失，无法计算文字匹配度。”
  - 规范化后为空 → 对应中文说明。
  - 双方都只有 1 个码点 → 单字比较，附“文本过短，单字比较参考有限”。
  - 超长文本（任一侧 > 2000 码点）→ 只比较前缀并提示“文本较长，仅比较前 2000 个字符”。
- 自评提交后的反馈（记得/没记住）显示同一比较结果；跳过不显示匹配。
- 不改变自评按钮可用性、保存、幂等、次数、错题与版本口径；没有新增数据库字段/schema（仍为 3）。

## 算法 v1

实现：`android/app/src/main/java/com/offlinequiz/app/model/ShortAnswerTextMatch.kt`。

**规范化**（原输入不变）：

1. 按 Unicode 码点遍历，代理对不拆。
2. 全角 ASCII（U+FF01–U+FF5E）→ ASCII，U+3000 → 空格。
3. `Locale.ROOT` 小写。
4. 去空白（`isWhitespace`/`isSpaceChar`）。
5. 去固定列出的普通排版标点（中英文句读、引号、省略号、破折号、间隔号、书名号等）。
6. 保留：字母数字、否定词、条件词、`+ - % < > = / * ( ) [ ] { }` 等数学/比较符号；不删“的/了”等停用字。

**相似度**：

- 对 n ∈ {2,3} 取滑动码点 gram，按出现次数构成多重集。
- 重合数 = Σ min(countA, countB)；`Dice_n = 2×重合 / (A总数+B总数)`。
- 一侧不足 n、另一侧足够 → 该阶记 0 并参与平均；仅双方都不足才跳过该阶。
- 最终 = 可用阶等权平均，显示 0–100%（四舍五入）。
- 双方均只有 1 码点 → 单码点回退（相同 100%，不同 0%）。
- 无除零/NaN；线性遍历，内存与长度同阶且有界（上限 2000 码点/侧）。

手算校验（测试断言）：参考“甲乙丙”、输入“甲乙” → 2-gram 2/3、3-gram 0 → 最终 33%。

## 评估（实际运行）

完整表与局限见 `docs/design/short-answer-match.md`，数字来自 `ShortAnswerMatchEvaluationTest` 实际输出：

| 案例 | 最终分数 |
| --- | --- |
| 原文 | 100% |
| 同义改写 | 29% |
| 漏答 | 67% |
| 反义/否定（需要光照 vs 不需要光照） | **83%** |
| 数字变化（10→30 摄氏度） | **80%** |
| 条件变化（至少→至多） | 70% |
| 乱序（甲/乙互换） | 45% |
| 堆字（参考重复两遍） | 55% |
| 无关答案 | 0% |

局限如实写入设计文档与测试：同义改写可能低分、否定/数字/条件变化可能高分、语序变化只反映字符重合；该指标不能宣传为语义理解或正确率。高分反义案例未被隐藏或调参掩盖，测试将其固定为已知行为。

## UI 与状态接入

- `PracticeViewModel` 新增 `shortAnswerMatch` 状态；在 `applyState`、`updateShortText`、`revealReference`、提交反馈落盘后重算，不在 Composable 内计算。
- `applyState` 只在简答题、已展开（或自评反馈）时计算；其他题型/填空为 null。
- `PracticeScreen.kt` 在参考答案面板之后、自评按钮之前显示匹配卡；反馈卡在自评说明之后显示；未展开分支不渲染。
- 计算读取 `state.currentQuestion.referenceAnswer`（会话固定快照）；反馈读取 `feedback.userAnswer.text` + 同一快照。
- 有界同步计算，因此不存在“迟到结果覆盖后续题目”的竞态；切题时 `applyState` 会重算为 null。

## 实际验证

环境：`work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。

### 正式 Android JVM

`:app:testDebugUnitTest` → **27 个测试类，176 tests / 0 failures / 0 errors / 0 skipped**（03B R3 基线 154 + 新增 22）。

新增：

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| `ShortAnswerTextMatchTest` | 12 | 完全相同=100、手算部分重合、2/3 阶、多重集、重复堆字 55%、一侧过短记 0、Unicode 代理对、全半角/大小写/标点、单字回退、空态原因、长文上限、分数有限 |
| `PracticeViewModelShortAnswerMatchTest` | 9 | 展开前/仅输入不泄露；空输入与缺失参考空态且可自评；自评反馈同分且不进入自动统计；跳过无匹配；下一题清理且用新参考；恢复草稿+展开重算；改库不改旧会话快照；填空不显示 |
| `ShortAnswerMatchEvaluationTest` | 1 | 固定评估表数字与已知高分局限（反义/数字/条件等） |

正式结果 XML 副本：`work/03c/formal-results/`（27 个）；日志：`work/03c-tests-final.log`、`work/03c-full-build.log`。

### 两份 Codex 独立测试（原样，未改断言）

1. `WrongbookConflictReviewTest`：`tests=1 failures=0`，`REVIEW pending lookup: existing=active new=false`；日志 `work/03c/codex-conflict.log`，结果 `work/03c/codex-conflict-result.xml`。
2. `WrongbookCancelReviewTest`：`tests=1 failures=0`，`REVIEW canceled confirmation: new=false started=null`；日志 `work/03c/codex-cancel.log`，结果 `work/03c/codex-cancel-result.xml`。

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**，退出码 0，含真实样本 201 题；解析/拆空规则未改。

### 构建与仪器测试

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79 个 actionable tasks 全部执行**。

`:app:assembleDebugAndroidTest` 成功；仍为 **19 个**仪器测试。`adb devices` 为空，全部**未执行**；本阶段未改 schema。

### APK

- 路径：`outputs/apk/offlinequiz-03c-debug.apk`
- 大小：**43,608,857** 字节
- SHA-256：**`999B794C8C116040325A35D68E5C8D899F7BAF0E1AB41EAD5472ACAB4215E715`**
- applicationId：`com.offlinequiz.app`（未变）
- versionCode：**12**（> 11）；versionName：`0.3c-short-answer`
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`，与 03B R3/R2/R1 一致；`apksigner verify` 退出码 0。
- 权限：无 INTERNET/存储/相机；仅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
- 内容：243 个条目、0 个 `.pdf`；未检出 `sample.pdf`、`local-inputs`、`New project`、`C:\Users`。
- 旧 APK 全部保留且大小/哈希未变：03B R3（43,592,481 / `85676186…`）、03B R2（43,592,481 / `B887C37E…`）、03B R1、03B、03A R1、03A、02B R1、02B、02A 系列。

## 尚未验证（无设备）

- 19 个仪器测试未执行：真实 Room 事务/查询/迁移、持久化未做设备实测。
- 真机简答流程：输入→展开→匹配→记得/没记住→反馈；空输入心中作答；切题/旋转/强杀恢复；改库后旧会话显示旧参考。
- 长文本匹配的实际手感与滚动（上限提示）、超长简答输入性能。
- Compose UI 无自动化截图/点击测试；JVM 覆盖 ViewModel 状态与纯算法，UI 渲染为源码接线核查。
- 深色/无障碍、低存储写入失败；仅 debug 构建，无 release 签名。

## 最短真机步骤

1. 覆盖安装：`adb install -r "F:\New project\outputs\apk\offlinequiz-03c-debug.apk"`；确认 versionCode 12、同一调试签名，旧题库/练习数据仍在。
2. 进入含简答题的练习（或错题重练中的简答），输入一段文字；确认展开前页面上没有任何分数。
3. 点“展开参考答案”：
   - 期望：显示参考答案、“文字匹配度 N%”和“仅比较文字重合，不代表答案正确；请自行判断”。
   - 注意：**展开后输入框按既有行为锁定**，不能在展开态修改输入；要比较另一段文字，请返回/进入下一题或新一轮，在展开前输入。数值标点与长文边界的修正见 `docs/reports/03c-r1-dsh-report.md`（R1 起安装包为 `offlinequiz-03c-r1-debug.apk`）。
4. 清空输入后再展开或留在展开态：显示“尚未输入文字…”，不是 0%；“记得/没记住”和“跳过”仍可用。
5. 选择“记得/没记住”：反馈徽标显示相同匹配度，自评结果单独记录；完成本轮后检查自动正确率不含该题。
6. 输入后直接“跳过”：反馈不显示匹配，跳过单独统计。
7. 点“下一题”：分数清空，新题只按新题参考计算；旋转/强杀后恢复且已展开的草稿会重新显示同一分数。
8. 先在会话中展开看到分数，再回题库修改该题参考答案，重新进入旧会话：仍按旧快照比较，不改用新参考。
9. 有设备后运行 19 项仪器测试：`gradlew :app:connectedDebugAndroidTest`（环境变量见 `docs/reports/03c-device-checklist.md`）。

## 变更文件

**新增**

- `android/app/src/main/java/com/offlinequiz/app/model/ShortAnswerTextMatch.kt`：纯算法与结果模型。
- 测试：`ShortAnswerTextMatchTest`、`PracticeViewModelShortAnswerMatchTest`、`ShortAnswerMatchEvaluationTest`。
- `docs/design/short-answer-match.md`：算法、评估表与实际分数、局限。
- `docs/reports/03c-device-checklist.md`。

**修改**

- `viewmodel/PracticeViewModel.kt`：`shortAnswerMatch` 状态与在状态变化处重算。
- `ui/PracticeScreen.kt`：展开后/自评反馈的匹配卡与中文空态。
- `android/app/build.gradle.kts`：versionCode 12、versionName。
- `test/.../TestStores.kt`：测试辅助在 `resetMain` 前清空待运行协程（仅测试基础设施）。
- `docs/STATUS.md`、`docs/DSH_HANDOFF.md`：状态与交接。

未改：Room schema/迁移（仍为 3）、判题、自评/幂等/次数/错题/版本口径、历史页、PDF 解析与拆空规则、既有 Codex 验收测试与记录。

## 阻碍与边界

- 无实现阻碍。唯一限制仍是无安卓设备/模拟器；未安装 Android Studio、完整 SDK 或模拟器镜像，未改系统设置。
- 未提交、未推送、未发布；未覆盖 Codex 的任务说明与验收记录；未进入备份或发布。
- 完成本阶段并写报告后停止，等待用户手动通知 Codex 验收。
