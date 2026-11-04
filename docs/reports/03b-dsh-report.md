# 阶段 03B 报告：错题本与练习统计

日期：2026-11-03。执行者：DSH。状态：`PHASE_03B_READY_FOR_REVIEW`。无安卓设备：JVM/构建已实际执行，19 个仪器测试仅编译未执行，真机未测。

## 结论

已完成本地错题本、每题/每库累计统计和错题重练：

- 统计只以已落盘的 `practice_answers` 为事实，按 `libraryId + questionId` 聚合；活动、已完成、被新一轮取代的会话都计入，草稿和重复提交不计。
- 练习次数 = 自动作答 + 自评；跳过单列，不计次数/正确率，也不更新最近练习时间；自动正确率只在有自动作答时显示，否则显示“暂无自动作答”。
- 题目当前掌握状态只采用“与当前可编辑内容版本相符”的最后一条有效自动/自评记录；历史错误永久保留，答对不会删除历史错题。
- 改题后旧版本的正确作答不能证明新版已掌握：显示“题目已修改，待重新练习”并归入待复习，历史仍按当时快照展示。
- 错题本默认“待复习”，可切换“全部历史错题”；可按顺序/随机开始错题重练，题目集合与快照在创建时固定，随机不重复、恢复不重洗。
- 新增三个入口（题库统计卡、错题本、题目历史）都覆盖首次加载与失败重试，未重复 03A 的 loading 初值死锁。

未改 PDF 解析、仅顿号拆空、判题语义和 03A R1 已验收的加载修复；未做 n-gram、备份、联网或发布。

## 用户流程

题库详情 → 练习统计卡（总览 + “打开错题本”）→ 错题本（待复习/全部历史错题、顺序/随机重练）→ 题目历史（当前状态、累计次数、逐条历史、校对入口）→ 完成重练后统计与次数更新 → 关闭重开仍从数据库重建。

## 统计规则（实现含义）

| 指标 | 定义 |
| --- | --- |
| 练习次数 | `automatic` 记录数 + `self_assessment` 记录数；不含 `skipped` |
| 自动正确/错误 | `kind=automatic` 且 `result=correct/incorrect` |
| 自评记得/没记住 | `kind=self_assessment` 且 `result=remembered/forgotten`，不混入自动正确率 |
| 自动正确率 | `自动正确 ÷ (自动正确+自动错误)`；分母为 0 时界面显示“暂无自动作答”，不显示 0% 或 NaN |
| 跳过 | `kind=skipped`，单列；不计练习次数，不清除错题，不改变掌握状态，不覆盖最近练习时间 |
| 最近练习时间 | 所有 `automatic`/`self_assessment` 记录中最大的 `submittedAt`；跳过不参与 |
| 有记录题目 | 有任意已提交记录（含仅跳过）的不同 `questionId` 数 |
| 历史错题 | 曾经出现 `automatic+incorrect` 或 `self_assessment+forgotten` 的题；永久保留 |
| 当前状态 | 内容版本相符的最后一条自动/自评：`incorrect/forgotten` → 待复习；`correct/remembered` → 本次已通过（标明自动或自评） |
| 时间相同 | SQL 按 `submittedAt, id` 排序，Kotlin 侧用相同次序；同一时间戳的“最后一条”由稳定的 `id` 次序决定，不依赖数据库无 ORDER BY 的返回顺序 |

统计没有任何独立计数器表：每次从 `practice_answers` 重新聚合，因此不会与历史漂移。聚合在 ViewModel 的 `withContext(Dispatchers.Default)` 中完成并存入界面状态，Compose 重绘只读结果，不重新解码历史。

## 历史版本关联方案

- 每条作答本来就保存 `referenceSnapshotJson`（提交时题目的练习快照）。历史界面用它显示**当时的**参考答案和用户答案格式，而不是改后的当前内容。
- 版本相同与否由 `PracticeContentVersion.canonical(snapshot)` 判定：对 `type`、`stem`、`options`（key+text 顺序）、`answers`（顺序）、`acceptableAnswers`（按空）、`referenceAnswer` 做统一 trim 的规范化字符串比较。
- 明确**不**参与比较：原编号、`position`、`editedAt`、时间戳、`libraryId`。单纯重新编号/排序不会让已掌握题失效；题干/选项/答案/可接受答案/参考答案/题型变化则会。
- 当前状态只使用 `canonical(记录快照) == canonical(当前题目快照)` 的记录；旧版本记录只更新历史、不参与当前掌握判断，并标记“内容已修改”。这样“旧会话在改题后完成”也只能更新旧版本历史。
- 未单独存“内容版本”列：版本由已保存的快照推导，不需要迁移期回填，也不会出现版本列与快照不一致的漂移。

## 错题本与重练

- 历史错题按题去重，展示题型、原编号、题干摘要、历史错误次数（自动答错/自评没记住分开）、当前状态、最近练习时间、是否当前可练习；提供“查看历史”和“校对题目”。
- 默认筛选“待复习”（含“题目已修改，待重新练习”），可切换“全部历史错题”（含已通过）。
- 重练只对“待复习且当前符合原练习资格规则（题型支持、已确认或无需校对、结构有效）”的题创建；仓库层再次按 id 过滤，绝不混入全库。
- 创建时固定入选题目和当前内容快照；随机顺序一轮无重复，`loadState` 恢复不重洗。
- 与已有未完成会话冲突时，界面弹窗提供“继续现有练习 / 开始错题重练 / 取消”，不会静默替换；旧会话与历史保留。
- 会话新增 `source`（`normal`/`wrongbook`）；重练沿用同一套幂等提交、草稿/反馈恢复、保存失败保护，作答进入同一题历史。

## 数据与迁移变化（Room v3）

- `practice_sessions` 新增 `source TEXT NOT NULL DEFAULT 'normal'`；`practice_answers` 新增 `(questionId)` 索引（历史查询）。
- 新增显式 `MIGRATION_2_3`：`ALTER TABLE practice_sessions ADD COLUMN source TEXT NOT NULL DEFAULT 'normal'` + 创建索引；不重建表、不清库。
- `VERSION = 3`，保留 `1.json`、`2.json`，新增 `3.json`；`create()` 注册 `MIGRATION_1_2, MIGRATION_2_3`，未启用 destructive fallback。v1 库仍走 1→2→3。
- 新增只读 DAO 查询：按库 JOIN 会话取全部已提交作答、按题过滤；通过 `RoomPracticeStore` 映射为领域对象。
- 新增 `PracticeStatsRepository`（无缓存表）与纯逻辑 `PracticeStatsCalculator`。

Schema 静态自检（辅助，不是设备测试）：用 Python `sqlite3` 按 `2.json` 建库并执行 `MIGRATION_2_3` 的 SQL，与按 `3.json` 建表的结果比较 `PRAGMA table_info` / `index_list`（含类型、NOT NULL、默认值 `'normal'` 和索引名）：**四个表全部一致**。脚本：`work/03b/schema-check.py`。

## 实际验证

环境：项目内 `work/jdk-21.0.13+9`、`work/android-sdk`（platform 36 / build-tools 36.0.0）、`work/gradle-user-home`、Gradle 8.13、`--no-daemon`。以下均为本机实际执行。

### Android JVM 单测

`:app:testDebugUnitTest` → **18 个测试类，115 tests / 0 failures / 0 errors / 0 skipped**（03A R1 基线 83，新增 32）。

本次新增/相关：

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| `PracticeStatsCalculatorTest` | 11 | 先错后对保留历史并更新状态、自评与自动分开、零分母显示“暂无自动作答”、跳过不改状态/正确率/最近练习、仅跳过题、改题后旧正确不能证明新版、最后一条决定状态与同时间戳稳定次序、版本比较字段边界、历史新旧排序、历史文案、涉及题数 |
| `PracticeStatsRepositoryTest` | 4 | 被取代会话仍计数、不同库隔离、跨库题目历史不可读、幂等重试不双计、读取失败向上抛出（不伪装零统计） |
| `PracticeWrongbookRepositoryTest` | 5 | 仅选中的有效题、不可练习题被排除、空集合不建会话、随机固定无重复且恢复同序、重练与普通练习共用同一题历史 |
| `WrongbookViewModelEntryTest` | 7 | 首次加载成功/结束 loading、统计失败可重试、读取中重复调用只读一次、只对当前待复习可练习题开局、全部不可练给中文说明与校对入口、无待复习不崩溃、筛选切换 |
| `QuestionHistoryViewModelEntryTest` | 3 | 首次加载、失败重试、题目缺失仍可显示空历史 |
| `LibraryDetailStatsEntryTest` | 2 | 统计卡首次加载、统计失败显示错误并可重试恢复 |
| 原有 03A R1 及其前序测试 | 83 | 未改动断言，全部通过（含 11 项练习入口回归） |

### 共享解析回归

`tools/pdf-probe/run-tests.ps1` → **PASS=30 FAIL=0**，退出码 0，含真实样本 `201 questions, 58 underlines`。解析/拆空规则未改动。

### 构建

强制 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → **BUILD SUCCESSFUL，79 个 actionable tasks 全部执行**。

### 仪器测试：仅编译，未执行

`:app:assembleDebugAndroidTest` 成功，共 **19 个**仪器测试（03A 的 13 + 新增 6）：

- `QuizMigrationTest`（3）：v1→v2（保留）、**v1→v3**、**v2→v3**（source 默认 `normal`、作答保留、questionId 索引存在）。
- `PracticeStatsDatabaseTest`（4）：JOIN 历史包含被取代会话、跨库隔离、按题过滤与时间排序、source 持久化及默认值。
- 原有 `QuizDatabaseTest`（4）、`PracticeDatabaseTest`（5）、`PdfStorageDeviceTest`（3）不变。

`adb devices` 输出为空（无设备/模拟器），以上 19 项**全部未执行**；只验证了编译。JVM 假仓库通过不能替代真实 SQL/迁移。

### APK

- 路径：`outputs/apk/offlinequiz-03b-debug.apk`
- 大小：**43,526,941** 字节
- SHA-256：**`289576A5FF9FC6CBCF6BB1D7474EF99159180DE03A7802D6CD45443583E124AB`**
- applicationId：`com.offlinequiz.app`（未变）
- versionCode：**8**（>7）；versionName：`0.3b-wrongbook`
- minSdk 29 / targetSdk 36 / compileSdk 36
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`，与 03A R1/03A/02B R1 一致；`apksigner verify` 退出码 0。
- 权限：无 INTERNET/存储/相机；仅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
- 内容：243 个条目、0 个 `.pdf`；未检出 `sample.pdf`、`local-inputs`、`New project`、`LAPTOP-77T2UHAJ`、`C:\Users`。
- 旧 APK 全部保留且大小/哈希未变：02A 系列、02B、02B R1、03A（43,264,793 / `9555F9C7…`）、03A R1（43,281,185 / `F2047B99…`）。

## 尚未验证（无设备）

- 19 个仪器测试全部**未执行**：真实 SQLite JOIN/排序、v1→v3 与 v2→v3 迁移、v3 建库、source 列、索引与真实持久化。
- 真机安装覆盖 03A R1 后的真实 v2→v3 升级（旧题库/题目/确认状态/PDF 私有路径保留）。
- 真实题库上的统计数字、错题本列表/筛选、题目历史快照展示、改题后的“题目已修改，待重新练习”。
- 错题重练的顺序/随机、与未完成普通练习的冲突弹窗、完成后的次数更新、旋转/强杀后的恢复。
- 统计/历史页首次加载、失败重试、返回按钮的真实触控；Compose UI 无自动化截图/点击测试。
- 超大题库/大量历史下的统计耗时（已放到后台线程，但未做真机性能测量）。
- 深色模式、无障碍、低存储写入失败；仅 debug 构建，无 release 签名。

## 最短真机步骤

1. 覆盖安装：`adb install -r "F:\New project\outputs\apk\offlinequiz-03b-debug.apk"`；确认 versionCode 8、同一调试签名；打开旧题库，确认题目/确认状态/来源页仍可用，数据库从 v2 升到 v3 不闪退、不清库。
2. 做一轮普通练习：至少 1 题自动答错、1 题自动答对、1 题自评没记住、1 题跳过；返回题库。
   - 期望统计卡：练习次数 = 自动+自评（不含跳过）；跳过单列；自动正确率只在有自动作答时显示；最近练习时间不受跳过影响。
3. 打开错题本：默认“待复习”应含自动答错和自评没记住的题，自动/自评错误次数分开；切到“全部历史错题”仍能看到已答对的旧错题。
4. 点某题“查看历史”：显示提交时间、自动/自评/跳过、你的答案、**当时**参考答案；改过题的历史标注旧内容版本。
5. 回到错题本 →“顺序重练”或“随机重练”：只含待复习且可练习题；做题后返回，次数增加、答对的题不再待复习但仍留在全部历史错题。
6. 修改一道有待复习记录的题（改题干或答案）后回错题本：显示“题目已修改，待重新练习”，旧历史仍在；重练答对后显示已通过，历史错误次数保留。
7. 练习中/错题本/历史页旋转、按 Home、强制停止再重开：状态与统计保持一致；统计或历史读取失败时显示中文错误和“重试”。
8. 有设备后运行 19 项仪器测试：`gradlew :app:connectedDebugAndroidTest`（环境变量见 `docs/reports/03b-device-checklist.md`）。

## 变更文件

**新增**

- `android/app/src/main/java/com/offlinequiz/app/model/PracticeStats.kt`：统计模型、内容版本、聚合、历史文案。
- `android/app/src/main/java/com/offlinequiz/app/data/PracticeStatsRepository.kt`：只读统计仓库。
- `android/app/src/main/java/com/offlinequiz/app/viewmodel/WrongbookViewModel.kt`、`QuestionHistoryViewModel.kt`。
- `android/app/src/main/java/com/offlinequiz/app/ui/WrongbookScreen.kt`、`QuestionHistoryScreen.kt`。
- 测试：`PracticeStatsCalculatorTest`、`PracticeStatsRepositoryTest`、`PracticeWrongbookRepositoryTest`、`WrongbookViewModelEntryTest`、`QuestionHistoryViewModelEntryTest`、`LibraryDetailStatsEntryTest`、`TestStores.kt`（测试替身）。
- 仪器测试：`PracticeStatsDatabaseTest`；`QuizMigrationTest` 扩充到 3 项。
- `android/app/schemas/.../3.json`。

**修改**

- `data/QuizDatabase.kt`：schema v3、source 列、questionId 索引、`MIGRATION_2_3`、两条历史查询。
- `data/PracticeStore.kt` / `PracticeRepository.kt`：历史接口与 `startWrongbook`、session source。
- `model/PracticeModels.kt`、`PracticeJudge.kt`：source 字段、`PracticeEligibility.classify`（判定规则未变，仅抽出单题分类）。
- `viewmodel/LibraryDetailViewModel.kt`、`ui/LibraryDetailScreen.kt`：统计卡、错误重试、错题本入口、依赖注入构造。
- `ui/PracticeScreen.kt`：错题重练标题/结果标记。
- `MainActivity.kt`：错题本/历史路由与返回栈。
- `OfflineQuizApplication.kt`：共享 practiceStore、统计仓库。
- `android/app/build.gradle.kts`：versionCode 8、versionName。
- `docs/STATUS.md`、`docs/DSH_HANDOFF.md`、`docs/reports/03b-device-checklist.md`。

未改：PDF 解析/仅顿号拆空规则、`PracticeJudge` 判题语义、03A 幂等提交/草稿 revision/advance 事务、03A R1 加载修复。

## 阻碍与边界

- 无实现阻碍。唯一限制仍是无安卓设备/模拟器；未安装 Android Studio、完整 SDK 或模拟器镜像，未改系统设置。
- 未提交、未推送、未发布；未覆盖 Codex 的任务说明与验收记录；未进入 n-gram、备份或发布。
- 完成本阶段并写报告后停止，等待用户手动通知 Codex 验收。
