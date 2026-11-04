# 阶段 03D2 返工 01 报告：取消、请求隔离与导出发布时序

日期：2026-11-03。执行者：DSH。状态：`PHASE_03D2_R1_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，22 个仪器测试仅编译未执行；未打开真实用户库试恢复。

## 结论

已修复 03D2 验收的三处问题：

1. **导出目标打开时序**：拆成“本地生成 + 回读完整校验”与“发布复制”；只有校验成功后才调用系统目标 `openOutputStream`，生成/校验失败不打开目标。
2. **复制可取消**：目标复制改 8 KiB 分块，每块前后检查协程取消/请求取消；取消后不再写下一块，保留 `CancellationException`；目标已部分写入时提示“导出未完成”，不显示成功、不删用户旧文件。Codex 独立测试由 5 次写变为 **0** 次。
3. **请求身份/资源隔离**：新增 `BackupController`（实际被 `BackupViewModel`/`BackupScreen` 使用），每个 export/prepare/confirm 有独立 requestId + `AtomicBoolean` 取消标记 + Job；旧请求的成功/失败/取消/清理只在身份仍匹配时改 state。取消准备时通过 `onPrepared` 回调清理迟到产生的临时文件；DB 提交后真实 Job 迟到取消按 `PreparedRestore.committed` 报成功，不删 PDF、不重复插入；提交失败可重新操作。

正式 **34 类 232 tests / 0 failures**（03D2 基线 227 + `BackupControllerTest` 5）；7 项独立测试（原 6 + 新增导出取消）**7/0**；解析 **30/0**；强制构建 **79/79**。

## 1. 导出发布时序与取消

### 修复

`BackupService` 拆为：

- `prepareExport(isCancelled, onPrepared)`：一致性快照 → 私有临时 ZIP → `BackupReader` 回读校验 → 返回 `PreparedExport`；失败/取消删除临时文件。
- `publishExport(prepared, output, isCancelled)`：8 KiB 分块复制临时 ZIP 到目标；每块前后 `ensureActive()` + 请求取消检查；`CancellationException` 原样抛出；其他异常包装“导出未完成，目标文件可能不完整；请删除该文件后重试”；`finally` 删除临时 ZIP。
- `export(output)` 仅作为已持有目标流的便捷入口（测试用），内部同样走 prepare→publish。

`BackupController.export` 只在 `prepareExport` 成功后调用注入的 `openTarget()`；`BackupViewModel` 不再提前 `openOutputStream`，只在控制器请求时打开 SAF 目标。

### 验证

- Codex `BackupExportCancelReviewTest` 原样复跑：**1/0**，输出 `REVIEW export target writes after cancellation=0`（此前为 5）。
- 新增 `BackupControllerTest.targetOpensOnlyAfterPrepareSucceeds`：生成失败时 `openTarget` 调用 0 次，失败后成功路径才打开 1 次。
- 导出取消时保留 `CancellationException`；控制器捕获后只清理本请求资源，不把取消当普通失败/成功。

## 2. 请求身份与资源所有权

`viewmodel/BackupController.kt`（实际接入 `BackupViewModel`/`BackupScreen`）：

- 每次操作创建 `Request(id, AtomicBoolean cancel, Job)`；`isCurrent(id)` 才能改 state；旧请求的 catch/finally 不再无条件写 Idle/Error/Preview。
- 取消标记是该请求独有的 `AtomicBoolean`，新请求不会复用它；导出/准备/提交的迟到回调只清理自己的 `PreparedExport`/`PreparedRestore`。
- `prepare` 通过 `onPrepared` 回调在服务返回前登记结果；若交付时请求已取消/被替换，控制器在 catch/finally 调用 `operations.cancel(prepared)`，清理临时 ZIP，不会只改 UI 留后台任务。
- 提交：`Committing` 期间 `confirm` 重复调用被状态机拦截；Job 取消/通用异常时先检查 `PreparedRestore.committed`：
  - 已提交 → 仍显示成功（新库 ID），不误报“未写入”、不删已发布 PDF、不重复插入；
  - 未提交 → 错误“恢复已取消/未写入”，`prepared` 清空，随后可重新导出/准备/提交。
- `tryLeave` 在 `Committing` 外统一取消当前请求并清理；`Committing` 内返回 false，UI 顶部/系统返回均拦截。
- `BackupViewModel` 变薄：只解析 SAF URI（`openOutputStream`/`openInputStream` 作为 lambda 在控制器需要时调用），保留正常 Application 构造；`BackupScreen` 的返回/取消/选择回调/错误/成功全部走控制器。

### 控制器回归（5 项）

- 目标只在生成/校验成功后打开；失败不打开。
- A 挂起→取消→B 启动→A 迟到完成：B 状态保持成功，A 目标从未打开，A 临时资源被清理。
- A 准备挂起→取消→B 启动→A 迟到交付：B 保持 Preview，A 的 `PreparedRestore` 被取消清理。
- 确认重复点击只调用一次提交；提交已置 `committed` 后迟到 `CancellationException` 仍报成功。
- 提交失败后状态可恢复，能继续新导出（不卡在 Committing）。

## 实际验证

环境：`work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。

### 正式 Android JVM

`:app:testDebugUnitTest` → **34 个测试类，232 tests / 0 failures / 0 errors / 0 skipped**（03D2 基线 227 + `BackupControllerTest` 5）。

- `BackupServiceTest` 7/0、`BackupServiceIntegrationTest` 1/0、`BackupRouteTest` 1/0、`BackupControllerTest` 5/0 均通过。
- 结果 XML：`work/03d2-r1/formal-results/`（34 个）；日志：`work/03d2-r1-full-tests.log`、`work/03d2-r1-controller.log`。

### Codex 独立测试（原样，未改断言）

`work/codex-review-03d2/reviewer.init.gradle` 原 7 项：**7/0**——`BackupExportCancelReviewTest` 1/0（`writes after cancellation=0`）、`BackupSessionReviewTest` 2/0、数值 2/0、冲突 1/0、取消 1/0。日志 `work/03d2-r1/review-tests.log`，XML 在 `work/03d2-r1/`。

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**（含 201 题样本），`work/03d2-r1/parser-tests.log`。

### 构建/仪器测试

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79/79 任务执行**。

22 个仪器测试仍**只编译未执行**（`adb devices` 为空），其中 `BackupRoomDatabaseTest` 3 项覆盖一致性快照/聚合插入/重复 ID 回滚；不能声称真实 Room 已实测。

### APK

- 路径：`outputs/apk/offlinequiz-03d2-r1-debug.apk`
- 大小：**43,920,157** 字节
- SHA-256：**`CEA3E7BD4F8DEE55E06ABD7A92D56F437FFEEB8E7B354DFDEF632C8CC38C6E06`**
- versionCode **15**（>14）；versionName `0.3d2-r1-mobile-backup`；applicationId `com.offlinequiz.app`
- 签名：Android Debug，原证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`；`apksigner verify` 退出码 0
- 权限：无 INTERNET/广泛存储/相机；仅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
- 内容：243 条目、0 个 `.pdf`；未检出 `sample.pdf`、`local-inputs`、`New project`、`C:\Users`
- 旧 APK 全部保留（03D2 仍 `6BBF7EBF…`，03C R1 仍 `69956FFE…`）。

## 未验证/阻碍

- 真机 SAF、DocumentProvider 部分写入、旋转/强杀、系统返回、大文件/低存储/性能未测。
- 真实 Room 仪器测试仅编译未执行；未打开真实用户库。
- 导出取消后目标可能已有部分字节（平台无法回滚），界面只提示未完成，不显示成功、不删用户旧文件；未真机验证提示路径。
- 22 项仪器测试、Compose UI 触控/截图测试未执行；PDF 仍只做头部/字节/SHA-256；文件不加密。
- 仅 debug 构建；未提交/推送/发布。

## 变更文件

- `backup/BackupOperations.kt`（新）：可注入服务边界、`PreparedExport`。
- `backup/BackupService.kt`：prepare/publish 拆分、分块取消、`onPrepared` 清理钩子、commit 取消语义。
- `viewmodel/BackupController.kt`（新）：请求 ID/取消标记/资源所有权/提交 committed 判断。
- `viewmodel/BackupViewModel.kt`：改为控制器适配器，延迟打开 SAF 目标。
- `test/BackupControllerTest.kt`（新 5 项）。
- `docs/design/backup-format.md`、`docs/reports/03d2-r1-dsh-report.md`、`docs/reports/03d2-device-checklist.md`、`docs/STATUS.md`、`docs/DSH_HANDOFF.md`。
- `build.gradle.kts`：versionCode 15 / versionName。

未改：Room schema/事务/ABORT、新副本/新 ID/原数据/PDF 边界、解析/判题/统计/匹配规则、旧 APK、Codex 任务/验收记录。

## 阻碍

无实现阻碍；唯一限制是无安卓设备/模拟器。完成本返工并写报告后停止，等待用户手动通知 Codex 验收。
