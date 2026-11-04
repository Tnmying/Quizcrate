# 阶段 03D1 报告：离线备份格式、读写与恢复校验核心

日期：2026-11-03。执行者：DSH。状态：`PHASE_03D1_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，19 个仪器测试仅编译未执行，真机未测。**手机备份入口与真实 Room 恢复未实现**（03D2）。

## 结论

已完成离线备份核心：版本化 ZIP + UTF-8 JSON 格式、流式导出/读取、严格校验、以及“恢复为新题库副本”的新 ID 计划。

- 覆盖全部题库、校对后题目、完整会话（active/completed/superseded）、固定快照、作答、草稿/反馈进度和可选来源 PDF；不导出统计缓存。
- 不打包 Room/WAL，不写设备绝对路径/私有 PDF 路径；文件不加密（已写入格式文档）。
- 读取不落盘、不写 Room；完整通过校验后才返回 `BackupBundle`，之后才允许生成恢复计划；失败只抛 `BackupException`，没有半份计划。
- 恢复计划通过注入 ID 生成器为题库/题目/会话/作答全部换新 ID，并改写外键、快照题目 ID、历史快照与幂等键；旧库绝不被覆盖/关联。
- 本轮无生产入口、未试写真实库、未升版本/未复制新 APK；03C R1 包保留。

格式与字段、限制、完整性策略、03D2 接入/DAO 需求见 `docs/design/backup-format.md`。

## 实现内容

新增 `android/app/src/main/java/com/offlinequiz/app/backup/`：

| 文件 | 职责 |
| --- | --- |
| `BackupFormat.kt` | 格式常量、限制、错误码、`BackupException`、`BackupSource`/`BackupPdfSource` 接口 |
| `BackupModels.kt` | manifest/条目/PDF/bundle/answer/restore plan/summary/ID 生成器 |
| `BackupJson.kt` | manifest 与 NDJSON 编解码；必填/类型/枚举/嵌套规范校验 |
| `BackupValidator.kt` | 导出前/导入后的同一套跨记录校验（引用、索引、唯一性、状态/阶段、快照、作答对应） |
| `BackupWriter.kt` | 流式写 ZIP：manifest 先写、按库写 NDJSON/PDF、双读 PDF 计算并复核 SHA-256、取消检查、实际字节/条目/总字节限制 |
| `BackupReader.kt` | 流式读 ZIP：manifest 首条、逐行 UTF-8 严格解码、实际字节统计、重复/未知/缺失条目、CRC、PDF 头/SHA、计数核对 |
| `RestorePlanner.kt` | 纯计划：新 ID、全部引用重写、PDF 新相对路径、摘要 |

关键规则：

- 条目固定为 `manifest.json` + `libraries/<ordinal>/{questions,sessions,answers}.ndjson` + 可选 `resources/source.pdf`；manifest 记录题库元数据、计数、PDF 大小/SHA-256 和条目清单。
- 题目/会话/作答 DTO 保留业务字段；嵌套证据使用应用现有 `QuizJson` 规范 JSON 字符串并按“重新编码一致”校验；待校对题合法，不按“可练习”误拒。
- 缺失旧历史快照导出为 `null`，恢复后显示“快照不可用”，不伪造；改题前旧会话快照与改后题库题目都保留，不强行对齐。
- 拒绝：未来格式、重复/悬空 ID、非法索引、状态/阶段不一致、多活动会话、损坏/谎报大小 PDF、`..`/绝对路径、重复/未知/缺失条目、截断/非法 UTF-8 JSON、超限、取消。
- 限制默认：200 题库、2000 条目、2 MiB manifest、64 MiB/JSON 条目、8 MiB/行、1 MiB/字符串、1 GiB 总解压、5 万题目/会话、50 万作答/库、256 MiB PDF；测试注入更小值。
- 完整性：ZIP CRC32（逐条目）+ PDF SHA-256/`%PDF-` 头 + 清单计数与实际行数交叉核对；不信 `ZipEntry.size`/manifest 数字。格式文档明确说明未放逐条 SHA-256 及“不防主动伪造”的边界。

## 实际验证

环境：`work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。

### 正式 Android JVM

`:app:testDebugUnitTest` → **29 个测试类，206 tests / 0 failures / 0 errors / 0 skipped**（03C R1 基线 185 + 新增 21）。

- `BackupCoreTest`：**3 项通过**。两题库、五题型、顿号子空/可接受答案、待校对题、改题前历史、缺失历史快照、三种会话状态、自动/自评/跳过、未完成草稿、PDF；导出→读取→校验→新 ID 计划；核对每条记录/引用/资源；重复导入 ID 隔离；无 PDF 时仍恢复数据。
- `BackupRejectionTest`：**18 项通过**。未来版本、重复题目 ID、重复清单条目、重复 ZIP 条目（手写 STORED ZIP）、悬空作答会话、非法 currentIndex、损坏 PDF、`../`/绝对路径、未知条目、谎报 PDF 大小、超条目限制、截断 JSON、非法 UTF-8、缺条目、CRC 不一致、截断 ZIP、取消导出（输出 0 字节）。

正式结果 XML 副本：`work/03d1/formal-results/`（29 个）；日志：`work/03d1-full-tests.log`、`work/03d1-full-build.log`。

### Codex 既有独立测试（原样，未改断言）

`work/codex-review-03c/reviewer.init.gradle` 一次加入原三份，实际 **4 tests / 0 failures / 0 errors**：

- `ShortAnswerNumericReviewTest` 2/0（decimal 71%、ratio 62%）
- `WrongbookConflictReviewTest` 1/0（`new=false`）
- `WrongbookCancelReviewTest` 1/0（`new=false started=null`）

日志 `work/03d1/codex-review-tests.log`（最终代码复跑为 `codex-review-tests-final.log`），XML 在 `work/03d1/`。

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**，退出码 0，含真实样本 201 题；解析/拆空规则未改。

### 构建与仪器测试

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79 个 actionable tasks 全部执行**。此后新增最后一个 CRC 反例（仅测试源码）并把 writer 的 PDF 来源改为局部参数（无状态小重构），再次运行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 为增量 **最近一次 12 executed / 67 up-to-date、BUILD SUCCESSFUL**；206 项测试仍全部通过。

`:app:assembleDebugAndroidTest` 成功；仍为 **19 个**仪器测试。`adb devices` 为空，全部**未执行**；本阶段无 schema/迁移变化，未新增依赖。

### APK

本轮**未升级版本、未复制新 APK**（任务要求）；`outputs/apk/offlinequiz-03c-r1-debug.apk` 仍为最新可用包（43,608,861 bytes，SHA-256 `69956FFEE3BEF9413528488AF192161760D60AA4A9FFAABC14FA3B94C688D4A6`，versionCode 13）。全部旧 APK 保留、未覆盖。本轮构建产物只用于编译/测试检查，不代表手机备份已可用。

## 未接入/尚未验证

- 没有手机文件选择器、保存/打开入口、导入预览页、恢复确认页或成功提示；没有生产恢复按钮。
- 没有真实 Room 读写、事务恢复、PDF 复制/清理；未打开真实数据库试恢复。新增 DAO/事务需求已在格式文档列出，留 03D2。
- 19 个仪器测试、真实大文件/低存储/长时间导出、真机 ZIP 选择与取消、Room 一致性快照均未验证；JVM 使用合成内存数据，不是真实题库。
- 未做真实 PDF 大文件（当前测试为小型 `%PDF-1.4` 自制内容）；PDF 完整性只做头部 + SHA-256，未做完整 PDF 解析。
- 加密未实现（格式文档明确说明文件不加密）；未做 ZIP64 边界实测（限制 1 GiB 内）。
- 取消测试验证“取消后无输出”，但真实文件系统上的临时文件删除由 03D2 负责。

## 开发过程说明

开发中一次误用 PowerShell 文本回写导致 `BackupJson.kt` 的 UTF-8 中文被部分破坏；已将损坏副本隔离到 `work/03d1-BackupJson.corrupt.bak`，用 `write` 工具重写该文件并重新编译/测试。当前源码为有效 UTF-8，206 项正式测试通过。之后未再对含中文源码使用 PowerShell 文本回写。

## 变更文件

新增产品代码：`backup/BackupFormat.kt`、`BackupModels.kt`、`BackupJson.kt`、`BackupValidator.kt`、`BackupWriter.kt`、`BackupReader.kt`、`RestorePlanner.kt`。

新增测试：`BackupCoreTest.kt`（3）、`BackupRejectionTest.kt`（18）。

新增文档：`docs/design/backup-format.md`、`docs/reports/03d1-dsh-report.md`；更新 `docs/STATUS.md`、`docs/DSH_HANDOFF.md`。

未改：Room schema/迁移、真实数据路径、判题/自评/统计/错题/匹配规则、PDF 解析、APK 版本/签名/旧包、Codex 任务与验收记录。

## 阻碍与边界

- 无实现阻碍。无安卓设备/模拟器，无法做真机导出/恢复与 Room 事务验证。
- 未提交、未推送、未发布；未改项目外设置/凭据；未删除现有文件（损坏副本移到 `work/` 保留）。
- 完成本轮并写报告后停止，等待用户手动通知 Codex 验收；03D2 手机入口与原子写库另行下达。
