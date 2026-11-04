# 阶段 03D2 报告：手机备份/恢复新副本

日期：2026-11-03。执行者：DSH。状态：`PHASE_03D2_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，**22 个仪器测试仅编译未执行**，真机未测；未打开本机真实用户库试恢复。

## 结论

已在既有备份核心上接入手机入口与 Room/文件边界：

- 首页“备份与恢复”入口；系统 SAF `CreateDocument` 导出、`OpenDocument` 选包；无广泛存储权限。
- 导出：一致性 Room 快照 → 私有缓存临时 ZIP → 回读完整校验 → 复制给系统目标 → 删临时文件；失败明确“导出未完成”，不显示成功。
- 恢复：所选 URI 先复制为受限临时文件 → 后台/可取消读取校验并生成计划 → 展示摘要（题库/题目/练习记录/PDF 包含缺失/待校对）→ 明确“作为新副本导入，现有题库不变” → 用户确认 → PDF 暂存核验 → 单事务 ABORT 插入 → 成功打开新库。
- 取消/重复点击/提交中离页/旋转/进程重启/DB 后迟到取消均有状态机与资源边界；不覆盖旧库，重复导入独立。
- 新交付 APK `outputs/apk/offlinequiz-03d2-debug.apk`（versionCode 14，原签名）。

## 实现

### 手机入口/UI

- `ui/AppRouteState.kt`：新增 `backup` 路由及进入/返回；路由持久化沿用既有 Saver。
- `ui/HomeScreen.kt`：新增“备份与恢复”按钮。
- `MainActivity.kt`：`Route.BACKUP` → `BackupScreen`；成功后可打开新题库。
- `ui/BackupScreen.kt`：导出/恢复按钮、加载/校验/预览/提交/成功/失败状态、预览摘要与“作为新副本导入”说明、确认/取消、成功打开新库；`BackHandler` 与顶部返回统一；提交中拦截离页并提示。
- `viewmodel/BackupViewModel.kt`：状态机（Idle/Exporting/Preparing/Preview/Committing/Success/Error）、同操作防重、预览取消置位、提交中 `tryLeave()` 返回 false、旋转由 ViewModel 保持、进程重启不持久化请求；`onCleared` 不删除已提交/提交中的资源。

### 服务与 Room/文件

- `backup/BackupService.kt`：
  - `export`：读取快照→写 `cacheDir/backup/export-*.zip`→`BackupReader` 回读校验→复制给 SAF 输出→删临时；复制失败提示未完成。
  - `prepare`：所选流复制到 `cacheDir/backup/import-*.zip`（实际字节 ≤64 MiB）→`BackupReader`（协作取消）校验→`RestorePlanner` 生成新 ID 计划→返回预览；失败/取消只删临时。
  - `commit`：按计划从同一临时 ZIP 暂存 PDF 到 `files/libraries/<newId>/source.pdf.part`，校验字节/SHA/条目，改名；随后 `NonCancellable` 单次 `store.insert`，成功置 `committed`；提交前失败删除本次新建目录与临时 ZIP；提交后迟到取消不删 PDF、不重复插入。
  - `ANDROID_BACKUP_LIMITS` 保守：64 MiB 总解压/文件、32 MiB 单条目/PDF、2 MiB 行、256 KiB 字符串、200 库/2000 条目/2 万题/2 万会话/20 万作答；不把核心 1 GiB 直接搬入堆。
- `data/BackupDao.kt`：`readAll()` 在单事务读取四张全表（所有会话状态）；`insertAll()` 单事务 ABORT 插入；ID 计数查询。
- `data/RoomBackupDataStore.kt`：`readSnapshot()` 一致性快照并分组；`insert(plan)` 先逐 ID 查重，再用 `@Insert(ABORT)` 单事务插入；`openPdf` 只解析应用私有路径。
- `QuizDatabase` 新增 `backupDao()`；schema 仍为 3，无迁移。
- `Application.onCreate` 只清理 `cacheDir/backup/` 临时文件，不动 `files/libraries`；不自动重放恢复。

### 与 03D1 R1 语义的衔接

- 恢复计划保留原题库名、模式/状态/阶段/来源/时间/草稿/位置语义；不使用普通 `startSession`，不 supersede 活动会话。
- 活动练习、反馈、随机顺序、错题子集、历史快照、幂等键均按 03D1 R1 计划重建；每库最多一个活动会话随副本保留。
- PDF 缺失：导出省略并在清单标记；恢复数据可用、原页不可用；声明包含但缺失/损坏整次失败。

## 实际验证

环境：`work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。

### 正式 Android JVM

`:app:testDebugUnitTest` → **33 个测试类，227 tests / 0 failures / 0 errors / 0 skipped**（03D1 R1 基线 218 + 新增 9）。

新增：

- `BackupServiceTest` **7/0**：导出→读取→预览→提交并生成 PDF；重复导入独立新 ID/目录；非法输入不写库且清临时；校验中取消清临时；DB 插入失败回滚并删除本次暂存目录；提交后迟到取消保留 PDF、重复确认不二次插入；缺 PDF 仍恢复数据。
- `BackupServiceIntegrationTest` **1/0**：两库、五题型、待校对、改题前历史、缺失旧快照、三状态会话、草稿/反馈、随机/错题子集语义、可选 PDF，经真实 `BackupService.export→prepare→commit` 往返；断言原快照未变、两库/记录/引用/新活动练习/重复导入独立。
- `BackupRouteTest` **1/0**：备份入口路由可进入/持久化/返回。

结果 XML：`work/03d2/formal-results/`（33 个）；日志：`work/03d2-final-tests.log`、`work/03d2-full-tests.log`、`work/03d2-full-build.log`、`work/03d2-service.log`、`work/03d2-integration.log`。

### Codex 独立测试（原样，未改断言）

`work/codex-review-03d1/reviewer.init.gradle` 原 6 项：**6 tests / 0 failures**（`BackupSessionReviewTest` 2、数值 2、冲突 1、取消 1），日志 `work/03d2/review-tests.log`，XML 在 `work/03d2/`。

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**（含 201 题样本）；解析/拆空规则未改，`work/03d2/parser-tests.log`。

### 构建/仪器测试

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79/79 任务执行**。此后增加导出前的全库行数上限守卫（`RoomBackupDataStore`），再次运行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 为增量 **12 executed / 67 up-to-date、BUILD SUCCESSFUL**；227 项测试全部通过。

仪器测试新增 `BackupRoomDatabaseTest`（3 项：一致性快照读取全状态、聚合插入保留原库、重复 ID 整次回滚），总数 **22**；`adb devices` 为空，**全部未执行**，不能声称真实 Room 已实测。

### APK

- 路径：`outputs/apk/offlinequiz-03d2-debug.apk`
- 大小：**44,517,422** 字节
- SHA-256：**`6BBF7EBFADA112F64BFAC8BBDDC08500F8568DA3B6BB7C97A027E4C424364210`**
- versionCode **14**（>13）；versionName `0.3d2-mobile-backup`；applicationId `com.offlinequiz.app`
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`（原证书）；`apksigner verify` 退出码 0
- 权限：无 INTERNET/广泛存储/相机；仅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
- 内容：243 条目、0 个 `.pdf`；未检出 `sample.pdf`、`local-inputs`、`New project`、`C:\Users`
- 旧 APK（含 03C R1）全部保留，未覆盖。

## 未验证/未接入/阻碍

- **真机**：SAF 导出/选包、DocumentProvider 行为、旋转/进程重启、系统返回、提交中拦截、实际触控、大文件/低存储/性能均未测。
- **真实 Room**：`BackupRoomDatabaseTest` 仅编译，未在设备执行；JVM 服务测试用内存 `BackupDataStore`，不能替代真实 Room 事务/回滚/WAL 快照。
- 真实用户库未打开、未试恢复；没有真实题库/PDF 备份样本。
- PDF 只做 `%PDF-` 头 + 实际字节/SHA-256，不做完整 PDF 解析；未加密（文档/界面已说明）。
- 导出到系统 DocumentProvider 无法保证原子替换；失败时可能留下部分目标文件，界面明确“未完成”，不删除用户原文件。
- 极端进程强杀在“PDF 已发布、DB 未提交”窗口会留下新目录；由既有孤儿清理（>1 小时）处理，未做真机强杀验证。
- 未做 Compose UI 自动化/截图测试；入口/状态机为源码接线与 JVM 路由测试。
- 仅 debug 构建，无 release 签名；未发布/提交/推送。

## 变更文件

新增：`data/BackupDao.kt`、`data/RoomBackupDataStore.kt`、`backup/BackupDataStore.kt`、`backup/BackupService.kt`、`viewmodel/BackupViewModel.kt`、`ui/BackupScreen.kt`、`androidTest/BackupRoomDatabaseTest.kt`、`test/BackupServiceTest.kt`、`test/BackupRouteTest.kt`。

修改：`BackupReader.kt`（协作取消）、`QuizDatabase.kt`（backupDao）、`AppRouteState.kt`、`HomeScreen.kt`、`MainActivity.kt`、`OfflineQuizApplication.kt`（临时清理）、`build.gradle.kts`（versionCode 14）、`docs/design/backup-format.md`、`docs/STATUS.md`、`docs/DSH_HANDOFF.md`。

未改：Room schema/迁移、判题/统计/错题/匹配/解析规则、旧 APK、Codex 任务/验收记录。

## 阻碍

无实现阻碍；唯一限制是无安卓设备/模拟器。未安装 Android Studio/模拟器，未改项目外设置或凭据。完成本阶段并写报告后停止，等待用户手动通知 Codex 验收。
