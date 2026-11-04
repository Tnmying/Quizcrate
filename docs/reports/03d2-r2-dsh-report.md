# 阶段 03D2 返工 02 报告：就绪预览取消与导出关闭错误

日期：2026-11-03。执行者：DSH。状态：`PHASE_03D2_R2_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，22 个仪器测试仅编译未执行；未打开真实用户库。

## 结论

已修复 03D2 R1 验收的两项 P2：

1. **就绪预览取消**：`cancelPreview()` 不再因 `active == null` 提前返回；Prepare 完成、读取 Job 已结束后，仍会作废 `PreparedRestore`、释放临时 ZIP、回到 Idle。取消后 `confirm()` 不调用 commit；重复取消幂等；取消后可重新准备/导出；旧请求的迟到回调不清理新预览。
2. **目标流成功关闭**：导出成功路径显式 `target.close()`，只有关闭成功才进入 Success；write/flush 成功但 close 抛异常时显示“导出未完成”错误，不再用吞异常的 `closeQuietly` 完成成功路径。异常后的兜底清理仍静默关闭，但不会掩盖原错误或重复发布；目标句柄在打开交付边界取消时通过 `AtomicReference` 记录并由 finally 关闭，不用顺序调用 `service.cancel` 代替。

正式 **34 类 236 tests / 0 failures**（03D2 R1 232 + 4 项控制器补充回归）；9 项独立测试（原 7 + 新增 2）**9/0**；解析 **30/0**；强制构建 **79/79**。

## 1. 就绪预览取消

### 根因/修复

- `prepare` 成功后 `finish(request)` 清空 `active`，原 `cancelPreview()` 的 `active ?: return` 使 Preview 取消无效；`confirm()` 仍可提交。
- 修复：`cancelPreview()` 只要不是 `Committing`，就取消当前活动请求（若有）、调用 `operations.cancel(prepared)`、清空 `prepared`、state 回 Idle、`leaveBlocked=false`。即使没有活动 Job 也执行。
- 取消后 `confirm()` 因 `prepared == null`/state Idle 直接返回；重复取消无额外 `cancel` 调用；可立即重新 `prepare`/`export`。
- `prepare` 的 finally 仍以 `prepared !== delivered` 判断迟到资源，取消 A 后开始 B，A 迟到交付只清理 A，不影响 B。

### 回归

`BackupControllerTest` 新增/保留：

- `readyPreviewCancelReleasesPreparedAndBlocksConfirm`：Preview 取消→Idle、`cancelPreparedCalls=1`、confirm 0 次 commit；重复取消仍 1 次；随后重新 prepare 进入 Preview。
- Codex `BackupControllerReviewTest.cancellingACompletedPreviewMustReleaseItAndPreventConfirm` 原样通过（取消后 Idle、`cancelled=true`、commits=0）。
- 原有 A 取消→B→A 迟到完成/交付清理测试保持通过。

## 2. 导出成功必须包含关闭

### 修复

- 成功路径：`publishExport` 后执行 `withContext(io) { target.close() }`；close 抛 `IOException` 进入通用 catch，state 为 `Error("导出未完成：…")`，绝不进入 Success。
- 原 `closeQuietly(target)` 不再用于成功路径；仅用于已失败后的兜底清理。
- 目标打开在 `withContext(io + NonCancellable)` 中执行，并把返回的 `OutputStream` 写入 `AtomicReference`；若协程在 `withContext` 返回边界被取消/替换，finally 仍能关闭该句柄，不泄漏。
- 取消交付时先关闭目标再清理临时 ZIP；不会发布成功，不会重复发布。

### 回归（4 项新增）

- `targetCloseFailureIsNotSuccess`：write/flush 成功、close 抛 IOException → Error。
- `successfulExportClosesOnceAndPublishesOnce`：正常 close 一次、publish 一次、Success。
- `targetHandleDeliveredOnCancellationIsClosed`：openTarget 内部触发 cancel 后返回的句柄仍被关闭，state Idle。
- Codex `BackupControllerReviewTest.exportTargetCloseFailureMustNotReportSuccess` 原样通过（Error）。

## 实际验证

环境：`work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。

### 正式 Android JVM

`:app:testDebugUnitTest` → **34 个测试类，236 tests / 0 failures / 0 errors / 0 skipped**（03D2 R1 232 + 4：`BackupControllerTest` 现 9 项）。

- `BackupControllerTest` 9/0；`BackupServiceTest` 7/0、`BackupServiceIntegrationTest` 1/0、`BackupRouteTest` 1/0。
- 结果 XML：`work/03d2-r2/formal-results/`（34 个）；日志：`work/03d2-r2-full-tests.log`、`work/03d2-r2-controller.log`。

### Codex 独立测试（原样，未改断言）

`work/codex-review-03d2-r1/reviewer.init.gradle` 共 9 项实际 **9/0**：

- 新增 `BackupControllerReviewTest` **2/0**（预览取消后 Idle/cancelled/0 commit；close 失败 Error）。
- 原 `BackupExportCancelReviewTest` 1/0（`writes after cancellation=0`）、`BackupSessionReviewTest` 2/0、数值 2/0、冲突 1/0、取消 1/0。

日志 `work/03d2-r2/review-tests.log`，XML 在 `work/03d2-r2/`。

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**（含 201 题样本），`work/03d2-r2/parser-tests.log`。

### 构建/仪器测试

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79/79 任务执行**。

22 个仪器测试仍**只编译未执行**（`adb devices` 为空），包括 `BackupRoomDatabaseTest` 3 项；不能声称真实 Room 已实测。

### APK

- 路径：`outputs/apk/offlinequiz-03d2-r2-debug.apk`
- 大小：**43,920,157** 字节
- SHA-256：**`620165237995BCDA53824B9E4C4681ABE2EBA40E30032F7E2DC2A308E78ED468`**
- versionCode **16**（>15）；versionName `0.3d2-r2-mobile-backup`；applicationId `com.offlinequiz.app`
- 签名：Android Debug，原证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`；`apksigner verify` 退出码 0
- 权限：无 INTERNET/广泛存储/相机；仅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
- 内容：243 条目、0 个 `.pdf`；未检出 `sample.pdf`、`local-inputs`、`New project`、`C:\Users`
- 旧 APK 全部保留（03D2 R1 `CEA3E7BD…`、03D2 `6BBF7EBF…`、03C R1 `69956FFE…`）。

## 未验证/阻碍

- 真机 SAF/DocumentProvider close 失败/部分写入、旋转/强杀、系统返回、大文件/低存储/性能未测。
- 真实 Room 仪器测试仅编译未执行；未打开真实用户库。
- 目标关闭失败后的系统侧行为仅由注入 OutputStream 验证；真机 DocumentProvider 可能延迟报告写失败，未实测。
- 22 项仪器测试、Compose UI 自动化/截图未执行；PDF 仅头部/字节/SHA-256；文件不加密；仅 debug 构建。
- 仅验证注入服务/输出流，未声称手机触控或真实 DB 已复现。

## 变更文件

- `viewmodel/BackupController.kt`：就绪预览取消、目标关闭成功判定、打开句柄 AtomicReference/finally、异常语义。
- `test/BackupControllerTest.kt`：新增 4 项状态/资源回归（现 9 项）。
- `docs/reports/03d2-r2-dsh-report.md`、`docs/reports/03d2-device-checklist.md`、`docs/design/backup-format.md`、`docs/STATUS.md`、`docs/DSH_HANDOFF.md`。
- `build.gradle.kts`：versionCode 16 / versionName。

未改：Room schema/事务/ABORT、新副本/新 ID/原数据/PDF 边界、请求身份/分块取消、解析/判题/统计/匹配规则、旧 APK、Codex 任务/验收记录。

## 阻碍

无实现阻碍；唯一限制是无安卓设备/模拟器。完成本返工并写报告后停止，等待用户手动通知 Codex 验收。
