# 阶段 03B 返工 03 报告：补齐提交期间的离页保护

日期：2026-11-03。执行者：DSH。状态：`PHASE_03B_R3_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，19 个仪器测试仅编译未执行，真机未测。

## 结论

已修复 03B R2 验收的剩余 [P2]：`committing=true`/`pendingStartMode=null` 后确认弹窗关闭，但题目行的“查看历史/校对题目”等入口仍可直接切路由，提交慢时可离页并在返回后消费迟到的 `startedPracticeId`。

本轮把错题本**所有用户触发的离页路径**统一接入 `WrongbookViewModel.tryLeaveScreen()`：提交中拒绝并给出既有中文提示；提交结束/失败后恢复；确认复查期间离页先作废请求；成功创建后的自动进入新练习保持为合法完成路径，不经过该守卫。另加提交阶段模态等待对话框，从触控层覆盖题目行/顶栏/筛选等入口。

两份 Codex 独立测试原样复跑通过；正式回归 **154 tests / 0 failures**（24 类，03B R2 基线 149 + 新增 5），解析回归 **30 PASS / 0 FAIL**，强制构建 **79 任务全部执行**。

## 遗漏点与修复

验收指出的路径：

- `WrongbookScreen.kt` 把 `onOpenHistory/onEditQuestion` 直接传给题目行，行内两个 `TextButton` 无 `enabled` 限制；
- 提交阶段 `committing=true` 后确认弹窗关闭，题目行重新可见；
- `MainActivity` 收到回调即切路由，无提交保护；
- 慢写入时离页后，错题本不在组合中，成功导航 Effect 不运行；返回错题本可能消费迟到的 `startedPracticeId`。

修复（`viewmodel/WrongbookViewModel.kt`）：

```kotlin
fun tryLeaveScreen(): Boolean {
    if (committing) {
        notifyCommitInProgress()   // "正在创建新一轮，完成前不能取消或离开"
        return false
    }
    if (confirmRequest != null || pendingStartMode != null) {
        cancelPendingStart()       // 作废复查中的请求，迟到返回不能创建/导航
    }
    return true
}
```

- **提交中**：返回 false + 中文提示；不清理请求，写入照常完成。
- **确认复查中**：先作废 token/取消作业，再允许离页；迟到返回被 token 校验拦住。
- **空闲**：允许离页。
- **成功自动导航**：`startedPracticeId` 的 `LaunchedEffect` 不经过守卫，写完后仍自动进入新练习且只导航一次。

## 导航入口接线（源码逐项核查）

`ui/WrongbookScreen.kt`（最终行号）：

| 入口 | 行 | 接线 |
| --- | --- | --- |
| 系统返回 `BackHandler` | 57 | `if (viewModel.tryLeaveScreen()) onBack()` |
| 顶栏“返回” | 94 | 同上 |
| 错误态“返回题库” | 123 | 同上 |
| 全不可练“去校对题目” | 245 | `if (viewModel.tryLeaveScreen()) onEditQuestion(id)` |
| 题目行“查看历史” | 272（按钮 427） | 守卫 + `enabled = actionsEnabled` |
| 题目行“校对题目” | 275（按钮 428） | 守卫 + `enabled = actionsEnabled` |
| 确认弹窗“继续现有练习” | 310 | `if (viewModel.tryLeaveScreen()) { active?.let { onOpenPractice(it.id) } }` |
| 提交阶段模态等待 | 331–340 | `AlertDialog` 覆盖全屏；`onDismissRequest` 只提示，不关闭页面 |
| 行按钮可用性 | 270 | `actionsEnabled = !viewModel.committing && !viewModel.starting` |

- 提交阶段同时有三层：`committing` 守卫、按钮禁用、模态对话框遮罩。即使某个回调将来漏接守卫，触控也到不了页面。
- “查看历史/校对题目”功能未删除；提交结束/失败后 `actionsEnabled` 恢复。
- 自动进入新练习的 `LaunchedEffect(viewModel.startedPracticeId)` 保持原样，不被模态或守卫拦截。
- 以上为源码接线核查 + JVM 守卫测试；无设备，**不是 Compose 触控实测**。

## 实际验证（本机执行）

环境：`work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。

### 正式 Android JVM

`:app:testDebugUnitTest` → **24 个测试类，154 tests / 0 failures / 0 errors / 0 skipped**（03B R2 基线 149 + 新增 `WrongbookLeaveGuardTest` 5）。

新增用例：

| 用例 | 覆盖 |
| --- | --- |
| `leaveIsBlockedWhileCommittingAndAllowedAfterSuccess` | 提交中 `tryLeaveScreen()` 为 false 并显示中文提示；写入完成后恢复 true；会话只建一次、`startedPracticeId` 正常 |
| `leaveIsAllowedAfterACommitFailureAndNoSessionIsCreated` | 写入失败后守卫恢复，不留下会话，仍可离页 |
| `leaveDuringConfirmRecheckInvalidatesTheRequestFirst` | 复查挂起（不可取消返回）时离页：先作废、允许离开；迟到返回不创建、不导航 |
| `leaveIsAllowedWhenNothingIsInFlight` | 空闲时可离页，不产生副作用 |
| `successfulCommitNavigatesOnceAndLeavesAreReopened` | 成功只建一轮、完成导航只设置一次，之后可正常离页 |

结果与日志分开保存，避免被额外测试覆盖：
- 正式结果 XML：`work/03b-r3/formal-results/`（24 个 XML）
- 正式全量日志：`work/03b-r3-tests.log`
- 强制构建日志：`work/03b-r3-full-build.log`

### 两份 Codex 独立测试（原样，未改断言）

1. `work/codex-review-03b/tests/WrongbookConflictReviewTest.kt`（`reviewer.init.gradle`）
   - `tests=1 failures=0 errors=0`，输出 `REVIEW pending lookup: existing=active new=false`
   - 日志 `work/03b-r3/codex-conflict.log`，结果 `work/03b-r3/codex-conflict-result.xml`
2. `work/codex-review-03b-r1/tests/WrongbookCancelReviewTest.kt`（`reviewer.init.gradle`）
   - `tests=1 failures=0 errors=0`，输出 `REVIEW canceled confirmation: new=false started=null`
   - 日志 `work/03b-r3/codex-cancel.log`，结果 `work/03b-r3/codex-cancel-result.xml`

两次独立测试使用内存 Store，只证明 ViewModel 入口/取消逻辑；不声称真实 Room 事务或手机触控已复现。

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**，退出码 0，含真实样本 201 题；解析/拆空规则未改。

### 构建与仪器测试

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79 个 actionable tasks 全部执行**。

`assembleDebugAndroidTest` 成功；仍为 **19 个**仪器测试。`adb devices` 为空，全部**未执行**；本返工未改 schema，迁移测试与 03B R2 相同。

### APK

- 路径：`outputs/apk/offlinequiz-03b-r3-debug.apk`
- 大小：**43,592,481** 字节
- SHA-256：**`856761868759A0269D5294F7EFDACE152FBF79ECE14A09D4C84825E2E9AC421E`**
- applicationId：`com.offlinequiz.app`（未变）
- versionCode：**11**（> 10）；versionName：`0.3b-r3-wrongbook`
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`，与 03B R2/R1/03A R1 一致；`apksigner verify` 退出码 0。
- 权限：无 INTERNET/存储/相机；仅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
- 内容：243 个条目、0 个 `.pdf`；未检出 `sample.pdf`、`local-inputs`、`New project`、`C:\Users`。
- 旧 APK 全部保留且大小/哈希未变：03B R2（43,592,481 / `B887C37E…`）、03B R1（43,576,093 / `F6E6B21C…`）、03B、03A R1、03A、02B R1、02B、02A 系列。

## 尚未验证（无设备）

- 19 个仪器测试未执行：真实 Room 事务/查询/迁移、持久化未做设备实测。
- 真机提交阶段模态等待的触控遮挡：返回/系统手势/题目行历史/校对/筛选在提交中应全部无法离页，并在写入结束或失败后恢复。
- 真机确认复查期间离页/取消/继续；旋转/强杀发生在提交或复查阶段的行为。
- JVM 覆盖守卫语义与不可取消迟到返回，但 Compose 触点/导航实测仍需设备；源码接线为逐项核查，不称 UI 自动化通过。
- 超大题库/大量历史性能、深色/无障碍、低存储写入失败；仅 debug 构建，无 release 签名。

## 最短真机步骤

1. 覆盖安装：`adb install -r "F:\New project\outputs\apk\offlinequiz-03b-r3-debug.apk"`；确认 versionCode 11、同一调试签名，旧题库/练习数据仍在。
2. 保留一个未完成练习 → 错题本 → “顺序重练” → 确认开始新一轮。在“正在创建新一轮”模态出现期间，依次尝试：题目行“查看历史”“校对题目”、顶栏返回、系统返回/手势。
   - 期望：全部被拦住，只显示“完成前不能取消或离开”；写入完成后自动进入新练习一次。
3. 若能在调试构建中让写入变慢（故障注入）：确认模态期间无法离页；写入失败后模态消失、行按钮恢复、可正常返回/历史/校对，且不留下半成品会话。
4. 确认复查期间（“正在确认现有练习会话…”）点“继续现有练习/取消/返回”：应作废请求，迟到返回不创建、不跳转。
5. 正常确认重练：成功自动进入新练习；重复快速点击“确认”只创建一轮。
6. 在确认弹窗或提交模态上旋转屏幕：状态与保护保持，不产生迟到创建或意外跳转。
7. 有设备后运行 19 项仪器测试：`gradlew :app:connectedDebugAndroidTest`（环境变量见 `docs/reports/03b-device-checklist.md`）。

## 变更文件

- `android/app/src/main/java/com/offlinequiz/app/viewmodel/WrongbookViewModel.kt`：新增 `tryLeaveScreen()` 统一离页守卫（提交拒绝、复查先作废、空闲放行）。
- `android/app/src/main/java/com/offlinequiz/app/ui/WrongbookScreen.kt`：全部离页入口接入守卫；行按钮 `enabled`；提交阶段模态等待；继续现有练习走守卫。
- `android/app/src/test/java/com/offlinequiz/app/WrongbookLeaveGuardTest.kt`（新）：5 项守卫/恢复/成功导航回归。
- `android/app/build.gradle.kts`：versionCode 11、versionName。
- `work/03b-r2/run-review-test.ps1`：新增可选 `-LogDir`（默认不变）。
- `work/03b-r3/`：正式结果副本、两份独立测试日志/结果、解析回归日志。
- `docs/STATUS.md`、`docs/DSH_HANDOFF.md`、`docs/reports/03b-device-checklist.md`：状态、交接与 R3 真机补充。

未改：`QuizDatabase` schema/迁移、统计口径、内容版本匹配、历史入口/快照、判题、幂等、草稿/进度恢复、PDF 解析与拆空规则、`work/codex-review-03b*/` 验收测试与 init 脚本、R1/R2 已通过的令牌/取消逻辑。

## 阻碍与边界

- 无实现阻碍。唯一限制仍是无安卓设备/模拟器；未安装 Android Studio、完整 SDK 或模拟器镜像，未改系统设置。
- 未提交、未推送、未发布；未覆盖 Codex 的任务说明与验收记录；未进入 n-gram/备份/发布。
- 完成本返工并写报告后停止，等待用户手动通知 Codex 验收。
