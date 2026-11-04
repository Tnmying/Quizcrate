# 阶段 03A 返工 01 报告：练习页首次加载

日期：2026-11-03。执行者：DSH。状态：`PHASE_03A_R1_READY_FOR_REVIEW`。无安卓设备，真机未测。

## 结论

已修复 Codex 验收 [P1] 首次进入练习永远停在加载中。根因是 `PracticeViewModel` 用“正在加载”标志同时充当“已有加载任务”的防重入条件：新实例 `loading=true`，`load()` 一进门就被 `if (loading) return` 挡回，仓库一次都没读，`loading` 也没有机会变 false。

修复把“首帧展示”与“真实读取任务”分开：`loading` 只在确实有读取任务时置 true，重入由运行中的 `loadJob` 判断；读取无论成功、缺失还是抛错都会清除 `loading`；缺失/失败显示中文错误并提供“重试”和“返回题库”；旧会话的迟到回调由 generation 令牌拦截。

本次只改练习入口相关代码和测试，未改 Room schema/迁移、PDF 解析、仅顿号拆空规则、判题语义、幂等提交与草稿 revision 逻辑；未进入错题本、n-gram、备份或发布。

## 根因（Codex [P1]）

修复前 `PracticeViewModel.kt`：

```kotlin
var loading by mutableStateOf(true)          // 新实例即“加载中”

fun load(sessionId: String) {
    if (loadedSessionId == sessionId && state != null) return
    if (loading || saving || exiting) return // 首次调用必然在这里返回
    loading = true
    ...
}
```

- 新 ViewModel 的 `loading` 恒为 true，唯一的 `load()` 调用在启动协程前就 return，`repository.loadState` 执行 0 次。
- `loading` 只在协程体内变 false，协程从未启动，因此永远不会结束。
- `PracticeScreen` 在 `loading=true` 时只画 `CircularProgressIndicator`，所以顺序、新随机会话、重启后继续练习的首次进入都会永久转圈；返回再进是新 ViewModel，同样卡住。
- 错误分支（`error != null`）在缺陷下不可达，缺失会话也没有任何提示。

## 修复内容

文件：`android/app/src/main/java/com/offlinequiz/app/viewmodel/PracticeViewModel.kt`

1. `loading` 初始为 `false`。首帧的占位由界面原有 `state == null` 分支负责；该标志从此只表示“有真实读取任务在跑”。
2. `load(sessionId)` 记录本次请求的会话，交给 `startLoad`；新增 `retry()` 重试最近一次请求。
3. 防重入改用任务状态，而不是 `loading`：
   - 已成功加载同一会话且有 state → 直接返回（旋转/重入不重读、不覆盖内存里的未落盘输入）。
   - `loadJob` 仍活动且是同一会话 → 返回，不产生第二份并行读取。
   - 请求的是另一个会话 → 取消旧任务再开始新的。
   - 保留 `saving || exiting` 保护：保存作答或退出保存草稿期间不发起新的读取覆盖界面。
4. 每次加载带自增 `generation`；回调、异常分支和 `finally` 都只在 generation 仍是最新时才写状态，旧请求的迟到结果不能覆盖新会话。
5. `try / catch (CancellationException: rethrow) / catch (Exception) / finally`：成功、`loadState` 返回 null、抛错、被更新请求取消都保证最新那次 `loading=false`、`loadingSessionId=null`；不会再永久转圈。
6. 失败文案保持 `练习读取失败，请返回题库后重试`（中文），并在 finally 前设置 `error`。
7. 最小依赖注入：主构造函数改为 `(Application, PracticeRepository)`，生产用次构造函数 `(Application)` 读 `OfflineQuizApplication.practiceRepository`。既有 Codex 复核测试仍以 `PracticeViewModel(app)` 构造，未做任何测试适配。
8. 重复提交/重复前进的防护（`saving` 门闩、仓库幂等键、事务条件）未改；`canSubmitAutomatic`/`canSelfAssess`/`requestExit`/草稿去抖逻辑保持原样。

文件：`android/app/src/main/java/com/offlinequiz/app/ui/PracticeScreen.kt`

- 错误分支新增“重试”按钮（`viewModel::retry`），保留“返回题库”；两者在 `exiting` 期间禁用。其余界面结构、判题按钮、反馈/结果渲染未改。

### 未改动（修复边界）

- `QuizDatabase.kt` / schema `1.json`、`2.json` / `MIGRATION_1_2`：未改。
- `PracticeRepository` 读取、提交、幂等、草稿 revision、`advance` 语义：未改。
- `QuestionParser` / `Model` / 仅顿号拆空规则 / 判题 `PracticeJudge`：未改。
- 未新增依赖、未改 Gradle 插件或 Android SDK；仅 `build.gradle.kts` 提升版本号。

## 实际验证

环境：项目内 `work/jdk-21.0.13+9`、`work/android-sdk`（platform 36、build-tools 36.0.0）、`work/gradle-user-home`，Gradle wrapper 8.13，`--no-daemon`。全部为本机实际执行，不只是脚本准备。

### 1. 缺陷基线（修复前，实际执行，失败）

用 Codex 自己的 `work/codex-review-03a/reviewer.init.gradle` 把 `work/codex-review-03a/tests/PracticeEntryReviewTest.kt` 加入测试源，未改测试和 init 脚本：

```powershell
work/03a-r1/run-review-entry-test.ps1 -Rerun -LogName entry-review-baseline.log
```

结果：`1 test completed, 1 failed`，Gradle 退出码 1。当时 JUnit XML 报：

```text
java.lang.AssertionError: First load must reach the repository expected:<1> but was:<0>
```

与 Codex 验收记录一致（`sessionReads=0 loading=true`）。日志：`work/03a-r1/entry-review-baseline.log`。

### 2. 新增正式入口回归（实际执行，通过）

新文件：`android/app/src/test/java/com/offlinequiz/app/PracticeViewModelEntryTest.kt`。测试直接驱动真实 `PracticeViewModel`（JVM 下用 `Unsafe.allocateInstance` 分配 Application、不启动 Room/PDFBox；仅注入内存 `PracticeStore`），可控主调度器用 `StandardTestDispatcher`。不是只测 Repository。

11 个用例与结果（`tests=11 failures=0 errors=0`）：

| 用例 | 覆盖点 |
| --- | --- |
| `firstLoadReadsTheSessionShowsQuestionsAndEndsLoading` | 首次成功：仓库读取 1 次、loading 结束、题目/顺序/阶段正确 |
| `firstLoadOfMissingSessionShowsChineseErrorAndEndsLoading` | 首次缺失：读取 1 次、loading 结束、无 state、中文错误 |
| `readFailureEndsLoadingAndRetrySucceeds` | 读取抛错后结束加载；重试再读并成功 |
| `duplicateLoadWhileReadingPerformsOneRead` | 读取中重复调用只发起 1 次读取 |
| `reloadAfterSuccessDoesNotReadAgain` | 成功后重入不重读 |
| `lateCallbackOfSupersededSessionCannotReplaceCurrentState` | 旧请求被新会话取代后，迟到回调不覆盖当前状态、不写 error |
| `savedDraftIsRestoredWithoutRewritingIt` | 恢复已保存草稿，且加载本身不写草稿、不改 revision |
| `reentryKeepsUnsavedInputInsteadOfReplacingIt` | 旋转/重入保留尚未落盘的输入，随后正常保存一次 |
| `restoredFeedbackIsShownWithoutResubmitting` | 恢复已提交反馈：显示原结果、不再提交、不回到空作答 |
| `restoredCompletedSessionShowsSavedSummary` | 恢复已完成会话：阶段/汇总正确、currentQuestion 为空、不再提交 |
| `duplicateSubmitRecordsOnlyOneAnswer` | 连续两次提交只记录 1 条 |

命令与日志：`work/03a-r1/run-review-entry-test.ps1 -NoInit -TestFilter com.offlinequiz.app.PracticeViewModelEntryTest -LogName entry-tests-new.log`。

### 3. 证明新测试能抓到旧缺陷（实际执行）

临时只恢复旧守卫（`loading` 初始 true + `startLoad` 首行 `if (loading) return`），其余代码不变，重跑同一组测试：

```text
11 tests completed, 11 failed
```

11 个用例全部失败；随后立即恢复修复版，文件 SHA-256 与临时前一致（`219B43DB…A63E5F`），日志 `work/03a-r1/entry-tests-against-old-defect.log`。这说明新回归并非“怎么写都通过”。

### 4. 复核 Codex 独立入口测试（实际执行，通过，未改断言）

修复后原样重跑 `PracticeEntryReviewTest`（保留 `sessionReads==1`、`loading==false` 两条断言）：

```text
BUILD SUCCESSFUL，gradle_exit=0
REVIEW initial load: sessionReads=1 loading=false error=练习读取失败，请返回题库后重试
tests=1 failures=0 errors=0
```

日志：`work/03a-r1/entry-review-after-fix.log`、`work/03a-r1/entry-review-final.log`。未修改 `work/codex-review-03a/` 下的测试、init 脚本或断言。

### 5. 全量回归与构建（实际执行）

- Android JVM 全量：`12` 个测试类，**83 tests / 0 failures / 0 errors / 0 skipped**（原 72 + 新增 11）。命令：`:app:testDebugUnitTest`。
- 共享 PDF 解析回归：`powershell.exe -File tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**，退出码 0，含真实样本 `201 questions, 58 underlines`（解析/拆空规则未被改动）。
- 强制全量构建：`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79 个 actionable tasks 全部执行**（与 03A 同规模）。日志 `work/03a-r1/full-build-final.log`。
- `:app:assembleDebugAndroidTest` 成功产出测试 APK（1,279,217 字节），13 个仪器测试**只编译、未执行**。`adb devices` 输出为空（没有设备/模拟器）。

### 6. APK

- 路径：`outputs/apk/offlinequiz-03a-r1-debug.apk`
- 大小：**43,281,185** 字节
- SHA-256：**`F2047B99E5DD1B4B97591CBE20E9AE350DC1B390123201B11B1C5C613F72D0AE`**
- applicationId：`com.offlinequiz.app`（未变）
- versionCode：**7**（原 6）；versionName：`0.3a-r1-practice`
- minSdk 29 / targetSdk 36 / compileSdk 36（未变）
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`，与 03A/02B R1/02A 完全一致；`apksigner verify` 退出码 0。
- 权限：无 INTERNET/存储/相机；仍只有 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
- 内容：243 个条目、0 个 `.pdf`；未检出 `sample.pdf`、`local-inputs`、`New project`、`LAPTOP-77T2UHAJ`、`C:\Users`。
- 可复现性：最终源码连续两次构建得到同一 SHA-256。
- 旧产物全部保留：`offlinequiz-03a-debug.apk` 仍为 43,264,793 字节 / `9555F9C7…989A6`，02A 系列、02B、02B R1 均未删除或覆盖。

## 尚未验证（无设备）

以下均**未执行**，不能把编译或 JVM 测试当成真机通过：

- 真机上首次点击“顺序/随机/继续练习”是否立即出题（本修复的核心路径只有 JVM 状态层证据，无截图/录屏）。
- 真机上缺失会话/读取失败时“重试/返回题库”的触控与文案显示；错误态在正常数据下不易手工制造，需要故障注入。
- 旋转、锁屏、强杀后继续练习的恢复（草稿、反馈、随机顺序）在真实 Room + Compose 下的表现。
- 13 个仪器测试：`QuizMigrationTest`（v1→v2）、`QuizDatabaseTest`、`PracticeDatabaseTest`、`PdfStorageDeviceTest`。
- Room v1→v2 真实升级、真实 201 题样本、低存储写入失败、深色/无障碍、超大题库性能。
- 仅 debug 构建，无 release 正式签名。

## 最短真机步骤（针对本次修复）

1. 安装：`adb install -r "F:\New project\outputs\apk\offlinequiz-03a-r1-debug.apk"`；确认 versionCode 7、versionName `0.3a-r1-practice`、同一调试签名，旧题库与私有 PDF 仍在。
2. 打开有可练题的题库 → “练习” → “顺序练习”。
   - 期望：短暂加载后**立即显示第 1/N 题**，不再无限转圈；题干、题型、原编号正常。
3. 返回题库 → “继续练习”。
   - 期望：回到离开时的题目/阶段，不重新洗牌、不回到未作答。
4. 作答一题（含反馈）后旋转屏幕，再强制停止并重开。
   - 期望：反馈仍在，不重复记次；点“下一题”才前进。
5. 新开一轮随机练习，开始后强制停止重开。
   - 期望：顺序与离开前一致。
6. 错误态（缺失会话/读取失败）：正常数据下无法手工稳定制造，本次只有 JVM 状态层证据；若需真机复现，要另加调试用故障注入入口（不在本返工范围）。
7. 有设备后补跑 13 项仪器测试：`gradlew :app:connectedDebugAndroidTest`（命令与环境变量见 `docs/reports/03a-device-checklist.md`）。

## 变更文件

- `android/app/src/main/java/com/offlinequiz/app/viewmodel/PracticeViewModel.kt`：首次加载/防重入/重试/错误结束（本次修复主体）。
- `android/app/src/main/java/com/offlinequiz/app/ui/PracticeScreen.kt`：错误分支“重试”按钮。
- `android/app/src/test/java/com/offlinequiz/app/PracticeViewModelEntryTest.kt`：新增 11 项入口/状态回归。
- `android/app/build.gradle.kts`：versionCode 6→7，versionName `0.3a-r1-practice`。
- `tools/android-build.ps1`：新增可选 `-ApkName`，默认值仍为 `offlinequiz-03a-debug.apk`，旧行为不变（用于保留旧 APK）。
- `docs/STATUS.md`、`docs/reports/03a-device-checklist.md`、`docs/DSH_HANDOFF.md`：状态与入口步骤更新。
- 临时脚本/日志在 `work/03a-r1/`；未改 `work/codex-review-03a/` 的任何文件。

## 阻碍与边界

- 无实现阻碍。唯一限制仍是无安卓设备/模拟器；按要求未安装 Android Studio、完整 SDK 或模拟器镜像，未改系统设置。
- 未提交、未推送、未发布；未覆盖 Codex 的任务说明与验收记录；未进入错题本、长期统计、n-gram、备份或发布。
- 完成本返工并写报告后停止，等待用户手动通知 Codex 验收。
