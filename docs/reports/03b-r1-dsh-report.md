# 阶段 03B 返工 01 报告：会话冲突保护、每题历史入口、完整历史快照

日期：2026-11-03。执行者：DSH。状态：`PHASE_03B_R1_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，19 个仪器测试仅编译未执行，真机未测。

## 结论

已按 `docs/tasks/03b-revision-01.md` 完成三项修复，Codex 的独立冲突复现原样复跑通过（`REVIEW pending lookup: existing=active new=false`）：

1. **会话冲突保护**：活动会话查询分为“读取中 / 读取失败 / 成功且无会话 / 成功且有会话”；未知或失败时 UI 禁用开始且 ViewModel 拒绝创建；有会话时普通开始只弹确认，必须走独立的 `confirmStartPractice`，确认前重新读取会话，变化则再次确认。
2. **所有题都能看次数和历史**：全量题目列表每题新增“练习记录”入口（保留“校对题目”）；题目历史不再依赖错题身份；导航记录来源（题库/错题本），按钮和系统返回走同一路由，旋转恢复不丢 id/返回目的地。
3. **历史对照完整快照**：每条历史用其 `referenceSnapshot` 展示当时题型/原编号/题干/选项/用户答案/参考答案，可折叠展开；缺失快照明确提示“当时题目快照不可用”，仍保留时间、结果和用户答案。

未改统计口径、版本匹配、判题、幂等提交、草稿/进度恢复、PDF 解析/拆空规则；Room 仍为 schema 3（本返工无需迁移）；未进入 n-gram/备份/发布。

## R1：活动会话未知/读取失败时不得新建

`viewmodel/WrongbookViewModel.kt`

- 新增 `ActivePracticeState { LOADING, FAILED, NONE, PRESENT }`：`practiceLoaded` 只在**成功**读到首个活动会话后为 true；Flow 异常设置 `practiceLoadFailed=true`，不再伪装成“无会话”。新增 `retryActivePractice()`。
- `startPractice(mode)`（普通入口）：
  - `LOADING` → 提示“正在确认现有练习会话…”并拒绝；
  - `FAILED` → 提示“未能确认…请重试”并拒绝；
  - `NONE` → 创建；
  - `PRESENT` → 只设置 `pendingStartMode`（弹确认），**不创建**。
- 新增独立确认路径 `confirmStartPractice()`：先 `practiceRepository.activeSession(libraryId)` 重新读取；与弹窗时的 `pendingExpectedActiveId` 不一致时更新提示并**再次确认**（会话消失则视为无冲突）；一致才调用既有 `startWrongbook`。`cancelPendingStart()` 取消不产生任何写入。
- 保留 `starting` 防重复点击；创建失败只显示中文消息，不改变原活动会话；`createRound` 的 Started/NoEligible/Failed 分支都清理确认状态。
- 使用已有 `createSessionWithSupersede` 事务：确认新建时旧会话标为 superseded，旧作答/历史保留；取消、拒绝、失败路径不调用创建。

`ui/WrongbookScreen.kt`

- 开始按钮的可用性依赖 `activePracticeState`（LOADING/FAILED 时禁用），按钮下方显示对应中文状态与“重试确认会话”/“继续现有练习”。
- 确认弹窗改由 ViewModel 的 `pendingStartMode` 驱动，提供“继续现有练习 / 确认开始新一轮 / 取消”；弹窗内容实时读取当前 `activePractice`，会话变化时由 VM 重新提示。
- 系统返回 `BackHandler` 回到题库，与顶部“返回”一致。

同一类缺陷也补到了普通练习入口（不在验收发现内，但同属“失败=无会话”隐患）：`LibraryDetailViewModel` 增加 `practiceLoadFailed`、`retryActivePractice()`，普通 `startPractice` 在未成功读取/失败时拒绝，已有会话时必须 `confirmedReplace=true`（“开始新一轮”弹窗）才创建；`LibraryDetailScreen` 显示失败提示与重试。

## R2：任何题都可打开次数和历史

`ui/LibraryDetailScreen.kt`

- 全量题目列表每一行新增“练习记录”按钮（`onOpenHistory(questionId)`），并保留“校对题目”按钮/整行点击进入编辑；不需要先答错。
- 历史页对从未练习/仅跳过/仅正确/仅自评的题显示累计次数与空态。

`ui/AppRouteState.kt`（新）+ `MainActivity.kt`

- 用单一不可变 `AppRouteState` 管理 `route/libraryId/questionId/practiceSessionId/practiceReturn/editorReturn/historyReturn`，并用 `listSaver` 持久化全部 7 个字段（旋转/进程恢复不丢 id 与返回目的地）。
- 由题库进入历史 → `historyReturn=library`，返回题库；由错题本进入 → `historyReturn=wrongbook`，返回错题本；历史 → 校对 → 返回历史（`editorReturn=history`，保留 questionId）；从图书馆进入编辑器返回题库时清空 questionId。
- 历史、错题本新增 `BackHandler`；编辑器原有 `BackHandler` 走同一 `onBack`，系统返回与按钮使用同一路由，不会意外退出应用。

## R3：历史可以对照当时的完整题目

`model/PracticeHistorySnapshotView.kt`（新）

- `HistorySnapshotView.of(record)` **只用** `record.referenceSnapshot` 构造：`available/typeLabel/originalNumber/stem/options/userAnswer/referenceAnswer`；缺失快照时 `available=false`，题干/选项/参考答案为空，用户答案仍从记录渲染。
- 填空参考答案含各空与可接受答案；简答含参考内容；判断/选择显示对应题型。

`ui/QuestionHistoryScreen.kt`

- 顶部卡片明确标注“当前题目（题库现有内容）”，展示当前题型/原编号/题干/选项。
- 每条历史卡：提交时间、自动/自评/跳过与结果、用户答案、旧内容版本提示；历史快照区标注“当时题目（历史快照）”，折叠时显示 2 行题干 + “展开当时题目与参考答案”，展开后显示完整题干、当时的 A/B 选项文本、当时参考答案。
- 快照缺失时显示 `HistorySnapshotView.UNAVAILABLE_MESSAGE`（“当时题目快照不可用，仅保留已记录的作答、时间与结果。”），不补当前题干/选项，不崩溃。
- 初帧 `history == null` 显示加载中，避免闪现“题目已不存在”。

## 实际验证（本机执行）

环境：`work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。

### 全量 Android JVM

`:app:testDebugUnitTest` → **22 个测试类，142 tests / 0 failures / 0 errors / 0 skipped**（03B 基线 115 + 新增 27）。

新增/相关：

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| `WrongbookConflictTest` | 8 | 统计已好但活动查询挂起时拒绝新建；查询失败拒绝、重试恢复；无会话正常开始；有会话普通调用只弹确认；明确确认才新建且旧历史保留；取消保持原会话；会话变化再次确认；重复确认只建一轮 |
| `LibraryDetailPracticeGuardTest` | 2 | 普通入口活动查询失败拒绝并可重试；已有会话必须明确“开始新一轮” |
| `AppRouteStateTest` | 7 | 题库/错题本来源返回、历史→校对→历史、练习来源、返回首页；7 字段持久化恢复与截断防御 |
| `PracticeHistorySnapshotViewTest` | 6 | 改题干/改选项后仍显示旧题干与旧 A/B 选项；填空各空+可接受答案；简答参考内容；判断题型/编号；缺失快照保留用户答案并提示不可用 |
| `QuestionHistoryViewModelEntryTest`（扩充） | 7 | 原首次加载/失败重试/题目缺失 + 始终答对、仅自评记得、仅跳过、从未练习都能加载并显示正确次数/空态 |
| 03B 原有正式测试 | 115 | 未改断言，全部通过（统计口径、版本匹配、幂等、错题重练、入口加载等） |

### Codex 独立冲突复现（原样，未改断言）

`work/03b-r1/run-review-conflict-test.ps1`（使用 `work/codex-review-03b/reviewer.init.gradle` 临时加入测试源）：

```text
BUILD SUCCESSFUL，gradle_exit=0
tests=1 failures=0 errors=0
REVIEW pending lookup: existing=active new=false
```

即：活动会话查询尚未返回时调用与真实按钮相同的 `startPractice`，没有创建新会话，原会话仍为 active。日志 `work/03b-r1/conflict-review-after-fix.log`、`work/03b-r1/conflict-review-final.log`（后者在最终源码上复跑），结果 XML 由 Gradle 生成于 `android/app/build/test-results/testDebugUnitTest/`。本测试使用内存 Store，仅证明入口保护，不声称真实 Room 事务/界面已在设备运行。

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**，退出码 0，含真实样本 201 题。解析/拆空规则未改。

### 构建

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79 个 actionable tasks 全部执行**。日志 `work/03b-r1-full-build.log`。

### 仪器测试：仅编译，未执行

`:app:assembleDebugAndroidTest` 成功；仍为 **19 个**仪器测试（v1→v3/v2→v3 迁移、统计 JOIN/source、练习事务、PDF 等）。`adb devices` 为空，全部**未执行**。本返工未改 Room schema（仍为 3），迁移代码与 03B 相同。

### APK

- 路径：`outputs/apk/offlinequiz-03b-r1-debug.apk`
- 大小：**43,576,093** 字节
- SHA-256：**`F6E6B21C3489A44E6134CDD96E95342F21EC44B612C230A6FF8BA1E55CB97884`**
- applicationId：`com.offlinequiz.app`（未变）
- versionCode：**9**（> 8）；versionName：`0.3b-r1-wrongbook`
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`，与 03B/03A R1 等一致；`apksigner verify` 退出码 0。
- 权限：无 INTERNET/存储/相机；仅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
- 内容：243 个条目、0 个 `.pdf`；未检出 `sample.pdf`、`local-inputs`、`New project`、`C:\Users`。
- 旧 APK 全部保留且大小/哈希未变：03B（43,526,941 / `289576A5…`）、03A R1（43,281,185 / `F2047B99…`）、03A、02B R1、02B、02A 系列。

## 尚未验证（无设备）

- 19 个仪器测试未执行：真实 Room 事务/查询/迁移、持久化未做设备实测；本返工未改 schema，但仍不能把编译当迁移通过。
- 真机上的冲突弹窗与“继续/确认/取消”触控、活动查询失败注入、确认时会话变化、取消后继续原会话。
- 真机系统返回/手势返回：题库→历史→返回、错题本→历史→返回、历史→校对→返回历史，以及旋转/进程恢复。
- 真机历史卡展开/收起、长简答滚动、改题后旧题干/选项/参考答案显示、缺失快照提示（正常数据下难以制造）。
- Compose UI 无自动化截图/点击测试；JVM 呈现层测试证明界面使用的数据源，不等于设备 UI 实测。
- 超大题库/大量历史性能、深色模式、无障碍、低存储写入失败；仅 debug 构建，无 release 签名。

## 最短真机步骤

1. 覆盖安装：`adb install -r "F:\New project\outputs\apk\offlinequiz-03b-r1-debug.apk"`；确认 versionCode 9、同一调试签名，旧题库/确认状态/私有 PDF 仍可用。
2. 冲突保护：先开始一轮普通练习并作答 1 题（保持未完成），回题库 → 错题本 → 点“顺序重练”。
   - 期望：弹出“已有未完成的练习”，可选“继续现有练习 / 确认开始新一轮 / 取消”。
   - 选取消：仍在错题本，旧会话仍可从“继续练习”进入；再点重练并确认：新会话创建，旧会话/历史保留。
3. 任何题历史：题库详情题目列表 → 任一从未练习的题点“练习记录”。
   - 期望：显示累计 0 次与空态；再对一道始终答对的题、一道只跳过的题重复，次数/分类正确。
4. 返回路由：从题库进历史 → 返回应回题库；从错题本进历史 → 返回应回错题本；历史 → “校对/修改题目” → 返回应回历史；系统返回/手势与按钮一致，不退出应用。
5. 旋转/强杀：在上述历史/编辑/错题本页面旋转、按 Home、强制停止重开，确认仍能回到同一题库/题目/返回目的地。
6. 历史对照：先做一道单选题，再修改该题题干和 A/B 选项文字，回到题目历史展开旧记录。
   - 期望：顶部是“当前题目（题库现有内容）”，旧卡片显示“当时题目（历史快照）”、旧题干、旧 A/B 选项与当时参考答案，并有旧版本提示；折叠/展开正常。
7. 有设备后运行 19 项仪器测试：`gradlew :app:connectedDebugAndroidTest`（环境变量见 `docs/reports/03b-device-checklist.md`）。

## 变更文件

**新增**

- `android/app/src/main/java/com/offlinequiz/app/ui/AppRouteState.kt`：可测试的导航状态与持久化。
- `android/app/src/main/java/com/offlinequiz/app/model/PracticeHistorySnapshotView.kt`：历史卡呈现模型（只取快照）。
- 测试：`WrongbookConflictTest`、`LibraryDetailPracticeGuardTest`、`AppRouteStateTest`、`PracticeHistorySnapshotViewTest`。
- `work/03b-r1/run-review-conflict-test.ps1`：原样复跑 Codex 独立冲突测试的入口。

**修改**

- `viewmodel/WrongbookViewModel.kt`、`ui/WrongbookScreen.kt`：四态活动会话、确认路径、UI 状态与弹窗、返回。
- `viewmodel/LibraryDetailViewModel.kt`、`ui/LibraryDetailScreen.kt`：普通入口失败保护、重试、确认参数、每题“练习记录”入口。
- `ui/QuestionHistoryScreen.kt`：完整快照卡、展开/收起、当前/历史区分、返回、初帧加载。
- `MainActivity.kt`：统一 `AppRouteState`、题库→历史路由、返回来源。
- `test/.../TestStores.kt`：`addSession` 只在 ACTIVE 时更新活动会话；`WrongbookViewModelEntryTest`/`QuestionHistoryViewModelEntryTest` 适配新语义（历史答作使用 superseded 会话）。
- `android/app/build.gradle.kts`：versionCode 9、versionName `0.3b-r1-wrongbook`。
- `docs/STATUS.md`、`docs/DSH_HANDOFF.md`、`docs/reports/03b-device-checklist.md`。

未改：`QuizDatabase` schema/迁移、统计口径、内容版本匹配、判题、幂等提交/草稿 revision、PDF 解析与拆空规则、`work/codex-review-03b/` 下的验收测试与 init 脚本。

## 阻碍与边界

- 无实现阻碍。唯一限制仍是无安卓设备/模拟器；未安装 Android Studio、完整 SDK 或模拟器镜像，未改系统设置。
- 未提交、未推送、未发布；未覆盖 Codex 的任务说明与验收记录；未进入 n-gram/备份/发布。
- 完成本返工并写报告后停止，等待用户手动通知 Codex 验收。
