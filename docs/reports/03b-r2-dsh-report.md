# 阶段 03B 返工 02 报告：取消确认必须作废待完成请求

日期：2026-11-03。执行者：DSH。状态：`PHASE_03B_R2_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，19 个仪器测试仅编译未执行，真机未测。

## 结论

已修复 03B R1 验收的 [P2]：确认查询挂起期间取消，查询迟到返回 null 时仍会新建并导航。修复采用**请求令牌 + 作业句柄 + 挂起后校验 + 明确提交边界**，并统一了弹窗取消、点外部/系统返回、继续原练习三类入口；提交阶段之后不再接受取消，所有退出入口被拦截并显示状态。

两份 Codex 独立测试原样复跑（未改测试/断言）：

```text
WrongbookConflictReviewTest: tests=1 failures=0  REVIEW pending lookup: existing=active new=false
WrongbookCancelReviewTest:   tests=1 failures=0  REVIEW canceled confirmation: new=false started=null
```

正式回归 **149 tests / 0 failures / 0 errors**（23 类，03B R1 基线 142 + 新增 7），共享解析回归 **30 PASS / 0 FAIL**，强制构建 **79 任务全部执行**。

## 根因

修复前 `WrongbookViewModel.confirmStartPractice` 在启动协程前捕获 `mode/libraryId`，随后 `cancelPendingStart` 只清空 `pendingStartMode/pendingExpectedActiveId`，没有作废协程。当复查返回 `null` 时：

- `fresh?.id` 为 null；
- 被取消清空的 `pendingExpectedActiveId` 也是 null；
- `fresh?.id != pendingExpectedActiveId` 为 false，跳过“会话已变化”分支；
- 继续调用 `createRound`，新建会话并设置 `startedPracticeId`，界面随之跳转。

即“取消”只关了对话框，没有使请求失效。

## 修复设计

`viewmodel/WrongbookViewModel.kt`

1. **请求令牌与作业句柄**：每次确认生成自增 token 的 `ConfirmRequest(token, mode, libraryId)`，同时保存 `confirmJob`。每次挂起（`activeSession` 查询）返回后先检查 `isCurrentConfirm(token)`，不匹配立即返回，不做任何状态写入或创建。
2. **取消即作废**：`cancelPendingStart()` 在提交前清空 `confirmRequest`、`confirmJob?.cancel()`、清空 pending/expected、复位 `starting`；迟到的返回因 token 失效无法写入。
3. **协程取消不被吞掉**：查询外的 `catch (CancellationException)` 一律重新抛出，不会被通用 `catch (Exception)` 当作“无法确认会话”的普通失败继续执行。
4. **提交边界**：在“token 仍有效”检查之后才置 `committing = true` 并调用仓库写入。写入已发出后：
   - `cancelPendingStart()` 拒绝清除，显示“正在创建新一轮，完成前不能取消”；
   - 返回/关闭入口由 UI 拦截（见下）；
   - 写入完成/失败后在 `finally` 中复位。
   在 token 检查与 `committing = true` 之间没有挂起点，主线程串行执行，不存在取消插入窗口。
5. **旧请求不干扰新请求**：`finally` 只在 token 仍属于自己时复位 `confirmRequest/confirmJob/committing/starting`。取消后立即重新确认时，旧作业的返回与 finally 都因 token 不匹配而被忽略，不会清空或篡改新请求的状态，也不会重复写入。
6. **失败与再次确认**：复查失败保留对话框并允许重试；会话变化时更新 expected 并重新提示确认（第二次确认才创建）；成功确认、重复确认防重、失败重试等原有行为保持。
7. `observeActivePractice` 不再在重载/旋转时清空 pending 对话框（取消只能通过明确的取消入口），避免“重载被误当作取消”或反之。

`ui/WrongbookScreen.kt`

- 确认对话框在“正在复查”期间保持显示，确认按钮禁用，`取消`、`继续现有练习`、`onDismissRequest`（点外部/系统返回）统一调用 `cancelPendingStart()`；`继续现有练习` 先作废请求再打开原会话，迟到返回不会跳入新练习。
- 进入提交阶段后对话框关闭，开始按钮禁用，卡片显示“正在创建新一轮…”；“继续现有练习”在 `starting` 期间禁用；顶部“返回”和系统返回（`BackHandler`）改为提示“正在创建新一轮，完成前不能取消或离开”，不再离开页面。
- 复查失败/会话变化时对话框保留并显示对应中文状态。

`android/app/build.gradle.kts`：versionCode 10、versionName `0.3b-r2-wrongbook`。未改 Room schema（仍为 3）、统计口径、历史入口/快照、判题、幂等与进度恢复。

## 实际验证

环境：`work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。

### 正式 Android JVM（实际执行）

`:app:testDebugUnitTest` → **23 个测试类，149 tests / 0 failures / 0 errors / 0 skipped**（03B R1 基线 142 + 新增 7）。

新增 `WrongbookCancelTest`（7 项）：

| 用例 | 覆盖 |
| --- | --- |
| `cancelWhileConfirmReadPendingReturningNullCreatesNothing` | Codex 复现场景的正式版：挂起复查时取消、迟到返回 null，不创建、不设置 startedPracticeId |
| `cancelWhileConfirmReadPendingReturningTheOriginalSessionCreatesNothing` | 迟到返回原会话也不创建，原会话仍可继续 |
| `cancelWhileConfirmReadPendingReturningAnotherSessionDoesNotReopenDialog` | 迟到返回不同会话既不创建也不重新弹确认 |
| `cancelThenImmediateRetryCreatesOnceAndLateReturnCannotClobberIt` | 取消后立即重新确认；旧请求为不可取消的迟到返回，仍只创建一轮且不清空新请求状态 |
| `cancelDuringCommitIsRefusedAndTheCommittedWriteFinishesOnce` | 提交阶段取消被拒绝并提示，已发出的写入只完成一次 |
| `confirmFailureKeepsDialogAndAllowsRetry` | 复查失败保留对话框，重试后正常创建 |
| `duplicateConfirmWhilePendingCreatesOneRound` | 重复确认只创建一轮 |

原有 142 项（含 WrongbookConflictTest 8 项、入口/历史/快照/统计等）断言未改，全部通过。

结果与日志分开保存，避免被额外测试覆盖：
- 正式结果 XML 副本：`work/03b-r2/formal-results/`（23 个 XML）
- 正式全量日志：`work/03b-r2/formal-tests-final.log`
- 强制构建日志：`work/03b-r2-full-build.log`

### 两份 Codex 独立测试（原样复跑，未改断言）

使用 work 下 init 脚本临时加入测试源：

1. `work/codex-review-03b/reviewer.init.gradle` + `WrongbookConflictReviewTest`
   - `tests=1 failures=0 errors=0`，输出 `REVIEW pending lookup: existing=active new=false`
   - 日志 `work/03b-r2/codex-conflict-final.log`，结果 `work/03b-r2/codex-conflict-final-result.xml`
2. `work/codex-review-03b-r1/reviewer.init.gradle` + `WrongbookCancelReviewTest`
   - `tests=1 failures=0 errors=0`，输出 `REVIEW canceled confirmation: new=false started=null`
   - 日志 `work/03b-r2/codex-cancel-final.log`，结果 `work/03b-r2/codex-cancel-final-result.xml`

两次独立测试使用内存 Store，只证明 ViewModel 入口/取消逻辑；不声称真实 Room 事务或手机触控已复现。独立测试会覆盖 Gradle 默认测试结果目录，因此正式 149 项另存于 `formal-results/`。

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**，退出码 0，含真实样本 201 题；解析/拆空规则未改。

### 构建与仪器测试

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79 个 actionable tasks 全部执行**。

`:app:assembleDebugAndroidTest` 成功；仍为 **19 个**仪器测试。`adb devices` 为空，全部**未执行**；本返工未改 schema，迁移测试与 03B R1 相同。

### APK

- 路径：`outputs/apk/offlinequiz-03b-r2-debug.apk`
- 大小：**43,592,481** 字节
- SHA-256：**`B887C37E315688D8DD7AACC1D6BC23495CC83817986B901CE0BC7D26ADBA8E88`**
- applicationId：`com.offlinequiz.app`（未变）
- versionCode：**10**（> 9）；versionName：`0.3b-r2-wrongbook`
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`，与 03B R1/03B/03A R1 一致；`apksigner verify` 退出码 0。
- 权限：无 INTERNET/存储/相机；仅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
- 内容：243 个条目、0 个 `.pdf`；未检出 `sample.pdf`、`local-inputs`、`New project`、`C:\Users`。
- 旧 APK 全部保留且大小/哈希未变：03B R1（43,576,093 / `F6E6B21C…`）、03B（43,526,941 / `289576A5…`）、03A R1、03A、02B R1、02B、02A 系列。

## 尚未验证（无设备）

- 19 个仪器测试未执行：真实 Room 事务/查询/迁移、持久化未做设备实测。
- 真机确认弹窗的取消、点外部、系统返回、继续现有练习；复查挂起时取消/重试；提交阶段返回被拦截的真实触控与提示。
- 真机旋转/强杀发生在复查或提交阶段的行为（进程恢复、事务结果）。
- 迟到返回依赖注入在真机上难以手工制造；JVM 已用可控挂起与不可取消返回覆盖，但不等于设备实测。
- Compose UI 无自动化截图/点击测试；JVM ViewModel 测试证明逻辑，UI 入口接线为源码核查（取消/继续/关闭均调用同一 `cancelPendingStart`，提交阶段由 `committing` 拦截）。
- 超大题库/大量历史性能、深色/无障碍、低存储写入失败；仅 debug 构建，无 release 签名。

## 最短真机步骤

1. 覆盖安装：`adb install -r "F:\New project\outputs\apk\offlinequiz-03b-r2-debug.apk"`；确认 versionCode 10、同一调试签名，旧题库/练习数据仍在。
2. 先保留一个未完成练习，回题库 → 错题本 → “顺序重练” → 弹窗中点“确认开始新一轮”，随后立刻点“取消”（或点外部/按系统返回）。
   - 期望：不创建新练习、不跳转；旧会话仍可从“继续练习”进入。
   - 重复上述操作并选择“继续现有练习”：只打开原会话，不会迟到跳入新练习。
3. 在确认弹窗打开时旋转屏幕：对话框与待确认状态应保持，取消仍有效。
4. 若能在调试构建中让活动会话复查变慢（例如故障注入），在“正在确认…”时取消；复查迟到返回后不得创建或导航。
5. 正常确认“开始新一轮”：创建成功并进入重练；重复快速点击“确认”只创建一轮。
6. 复查失败（可注入读取失败）时：对话框保留、显示中文错误，重试后可正常创建。
7. 有设备后运行 19 项仪器测试：`gradlew :app:connectedDebugAndroidTest`（环境变量见 `docs/reports/03b-device-checklist.md`）。

## 变更文件

- `android/app/src/main/java/com/offlinequiz/app/viewmodel/WrongbookViewModel.kt`：请求令牌/作业句柄、挂起后校验、提交边界、取消作废、旧 finally 隔离、提交阶段提示。
- `android/app/src/main/java/com/offlinequiz/app/ui/WrongbookScreen.kt`：复查期间保留对话框、统一取消/继续/关闭入口、提交阶段拦截返回与继续、状态文案。
- `android/app/src/test/java/com/offlinequiz/app/WrongbookCancelTest.kt`（新）：7 项取消/提交边界回归。
- `android/app/build.gradle.kts`：versionCode 10、versionName。
- `work/03b-r2/`：正式结果副本、两份独立测试日志/结果、解析回归日志、构建日志与复跑脚本。
- `docs/STATUS.md`、`docs/DSH_HANDOFF.md`：状态与交接更新。

未改：`QuizDatabase` schema/迁移、统计口径、内容版本匹配、历史入口/快照、判题、幂等提交/草稿 revision、PDF 解析与拆空规则、`work/codex-review-03b*/` 下的验收测试与 init 脚本。

## 阻碍与边界

- 无实现阻碍。唯一限制仍是无安卓设备/模拟器；未安装 Android Studio、完整 SDK 或模拟器镜像，未改系统设置。
- 未提交、未推送、未发布；未覆盖 Codex 的任务说明与验收记录；未进入 n-gram/备份/发布。
- 完成本返工并写报告后停止，等待用户手动通知 Codex 验收。
