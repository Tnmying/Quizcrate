# 阶段 03D1 返工 01 报告：练习位置语义与完整进度校验

日期：2026-11-03。执行者：DSH。状态：`PHASE_03D1_R1_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，19 个仪器测试仅编译未执行，真机未测；手机入口与真实 Room 恢复仍未实现（03D2）。

## 结论

已修复 03D1 验收的两项业务问题，未改独立断言，未绕过校验：

1. **不再混淆题库位置与本轮索引**：`answer.position` 是 `session.questions` 的索引，`referenceSnapshot.position` 是原题库位置；校验改为与 `session.questions[answer.position]` 核对题目身份与当时内容，不再要求两个 position 相等。真实 `PracticeRepository.startWrongbook` + `skip`（题库第 6 题作为本轮第 1 题）与真实随机重排整轮均通过导出→读取→新 ID 计划往返。
2. **核对阶段要求的完整作答集合**：`answering` 要求恰好 `0..currentIndex-1`，`feedback` 要求 `0..currentIndex`，`completed` 要求全部；`superseded` 按其当时 phase 同规则。缺失、断档、超前或 phase 不匹配明确拒绝；`answering` 且 `currentIndex=0` 的空会话仍合法，允许 `referenceSnapshot=null`，但不允许缺作答本身。

正式 **30 类 218 tests / 0 failures**（03D1 基线 206 + 新增 12 项语义回归）；Codex 6 项独立测试（新增 2 + 既有 4）**6/0**；解析回归 **30/0**；强制构建 **79/79**。

## 1. 位置语义

### 根因

`BackupValidator` 把 `referenceSnapshot.position`（题库位置）与 `answer.position`（本轮索引）比较。错题子集/随机重排时两者通常不同，合法数据被拒绝：

```text
REVIEW actual subset: answerPosition=0 snapshotPosition=5
BackupException: 作答参考快照与作答不对应
```

### 修复

`BackupValidator.validateLibrary` 中：

- 先按 `answer.position` 取 `session.questions[answer.position]`，校验 `answer.questionId` 与该会话快照一致（原有）。
- 若 `referenceSnapshot != null`：
  - 其 `libraryId` 必须属于该库；
  - 与会话固定快照 `session.questions[answer.position]` **完全一致**（题目身份/题干/类型/选项/答案/可接受答案/参考内容/题库位置/`editedAt`）。
  - 不再比较 `snapshot.position` 与 `answer.position`。
- 这样既保留原题库位置，又能发现“用当前已编辑题库内容补旧记录”的不一致；`null`（旧快照不可用）仍允许。

### 正式回归（真实仓库，非合成）

新增 `BackupSessionSemanticsTest`：

- `realWrongbookSubsetKeepsLibraryPositionAndSessionIndex`：`TestLibraryStore` 中题库位置 5 的题，`PracticeRepository.startWrongbook` + `skip` 生成本轮索引 0 的作答；断言 `answer.position=0`、`snapshot.position=5`；导出→读取→校验→`RestorePlanner` 后新会话仍保留题库位置 5，作答索引/引用/幂等键正确。
- `randomReorderedSessionKeepsSessionOrderAndAnswerIndexes`：真实 `start(RANDOM, Random(7))` 的 3 题整轮逐题 `skip`+`advance`；断言会话顺序确实非 0,1,2、作答索引仍为 0,1,2、快照位置随随机顺序保留；导出/读取/计划后顺序与位置不变。
- `snapshotLibraryPositionMayDifferFromAnswerIndex`：合成位置 5/索引 0 的直接正例。

原正式样例（顺序题库）本身符合生产语义，未改写；新增真实错题子集/随机重排覆盖原样例未走到的路径。

## 2. 完整进度

### 规则

对每个会话按 phase/currentIndex 计算要求的作答位置集合，与实际 `answers` 的 position 集合精确比较：

| phase | 要求 |
| --- | --- |
| `answering` | `0 .. currentIndex-1`（当前题未提交；`currentIndex=0` 时为空集） |
| `feedback` | `0 .. currentIndex`（当前题必须已有提交） |
| `completed` | `0 .. questions.size-1`（全部） |
| `superseded` | 保留当时 phase，按同一规则 |

- 缺失/断档/超前（如 `answering` 阶段在 currentIndex 已有答案、`feedback` 缺当前题、`completed` 缺任一题）→ `BAD_INDEX` 拒绝。
- 空白集合：`active + answering + currentIndex=0 + answers=[]` 合法；`feedback + currentIndex=0 + answers=[]` 非法。
- 不补假作答、不丢现有记录、不静默重置进度；空会话/非法索引等既有保护保留。
- `referenceSnapshot=null` 允许（旧快照不可用）；对应位置的作答本身不可缺失。
- 反馈快照有内容时必须等于会话当时的固定快照；当前题库被编辑后的内容不能补旧记录（`referenceSnapshotContentMismatchIsRejected`）。

### 正式回归

`BackupSessionSemanticsTest` 覆盖：

- 正例：`active+answering+0` 空、`superseded+answering+0` 空、`feedback+0` 有作答、`feedback+0` 的 `referenceSnapshot=null`、位置 5/索引 0。
- 反例：`feedback+0` 无作答、`answering+currentIndex=2` 仅有 position 1、`answering+currentIndex=2` 含 position 2、`completed+currentIndex=3` 缺 position 2、`superseded+feedback+currentIndex=1` 缺 position 1、快照内容与会话不一致。
- 真实错题子集/随机整轮如上一节。

## 实际验证

环境：`work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。

### 正式 Android JVM

`:app:testDebugUnitTest` → **30 个测试类，218 tests / 0 failures / 0 errors / 0 skipped**（03D1 基线 206 + 新增 12）。

- `BackupSessionSemanticsTest`：12/0（真实错题子集、随机重排、三阶段/superseded 正反例、快照 null/不一致、位置不同）。
- `BackupCoreTest` 3/0、`BackupRejectionTest` 18/0 保持通过。

结果 XML：`work/03d1-r1/formal-results/`（30 个）；日志：`work/03d1-r1-full-tests.log`、`work/03d1-r1-full-build.log`、`work/03d1-r1-semantics.log`。

### Codex 独立测试（原样，未改断言）

`work/codex-review-03d1/reviewer.init.gradle` 一次加入三份独立测试，实际 **6 tests / 0 failures / 0 errors**：

| 测试 | 结果 | 输出 |
| --- | --- | --- |
| `BackupSessionReviewTest`（新增 2） | **2/0** | `REVIEW actual subset: answerPosition=0 snapshotPosition=5`；缺失反馈用例按预期抛 `BackupException` |
| `ShortAnswerNumericReviewTest`（既有） | **2/0** | decimal 71%、ratio 62% |
| `WrongbookConflictReviewTest`（既有） | **1/0** | `REVIEW pending lookup: existing=active new=false` |
| `WrongbookCancelReviewTest`（既有） | **1/0** | `REVIEW canceled confirmation: new=false started=null` |

日志 `work/03d1-r1/review-tests.log`，XML 在 `work/03d1-r1/`。未修改独立测试、init、任务或验收记录。

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**，退出码 0，含真实样本 201 题；解析/拆空规则未改。

### 构建与仪器测试

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79 个 actionable tasks 全部执行**。

`assembleDebugAndroidTest` 成功；仍为 **19 个**仪器测试。`adb devices` 为空，全部**未执行**；无 schema/迁移变化，无新依赖。

### APK

本轮**未升版本、未复制新 APK**；`outputs/apk/offlinequiz-03c-r1-debug.apk` 仍为最新可用包（43,608,861 bytes，SHA-256 `69956FFEE3BEF9413528488AF192161760D60AA4A9FFAABC14FA3B94C688D4A6`，versionCode 13）；全部旧 APK 保留。当前编译产物不代表手机备份可用。

## 未验证/未接入

- 手机文件选择器、导出/导入页面、导入摘要确认、恢复按钮、事务恢复：未实现（03D2）。
- 真实 Room 读取一致性/事务写入/回滚/PDF 复制清理、真实大文件/低存储/长时间导出：未执行。
- 19 个仪器测试、真机文件选择/取消/后台/旋转/强杀：未执行；JVM 用合成/内存 Store，不能替代真机。
- 03D2 的 DAO 需求与事务步骤仍见 `docs/design/backup-format.md`，本轮未改真实数据路径。

## 变更文件

- `android/app/src/main/java/com/offlinequiz/app/backup/BackupValidator.kt`：位置语义与完整进度校验。
- `android/app/src/test/java/com/offlinequiz/app/BackupSessionSemanticsTest.kt`：12 项真实/合成语义回归。
- `docs/design/backup-format.md`：位置语义、完整进度规则、测试覆盖更新。
- `docs/reports/03d1-r1-dsh-report.md`、`docs/STATUS.md`、`docs/DSH_HANDOFF.md`。

未改：备份 ZIP/JSON 格式与资源限制、新 ID 计划、安全路径、PDF 校验、既有 03C/03B 功能、APK 版本/签名/旧包、Codex 任务与验收记录。

## 阻碍与边界

- 无实现阻碍；唯一限制仍是无安卓设备/模拟器，无法做真机导出/恢复与 Room 事务验证。
- 未提交、未推送、未发布；未改项目外设置/凭据；未删除现有文件。
- 完成本返工并写报告后停止，等待用户手动通知 Codex 验收；03D2 另行下达。
