# 04D：练习主页拆分、统计口径与错题本按钮

日期：2026-11-04。实施者：Codex。用户明确授权亲自完成此前汇总的第 **3、4、6** 项，并要求留档供 DSH（大肥鱼）交接。本报告是实施和自检记录，不是独立评审结论。

## 已完成

### 第 3 项：练习主页与题目管理分开

- 题库练习主页保留概览、顺序/随机/继续/新一轮、统计和错题本入口；移除完整题目列表及筛选。
- 新增主页“题目管理 · 查看全部 X 题”按钮，打开 04C 的独立管理页，默认 ALL。
- “去校对待处理题”继续打开同一管理页，默认 ATTENTION，并从顶部展示筛选及首题。
- 题目编辑、历史入口都在管理页题卡中；编辑/历史返回仍回管理页，保留筛选；管理页返回练习主页。
- 保留用户最新要求：正常题不显示人工复核标签，只有“全部/需处理”筛选，不重新引入“未人工复核/已人工确认”分类。
- 将共享题卡 `QuestionRow` 原样从 LibraryDetailScreen 移入独立文件，页面职责明确。

### 第 4 项：遍数、题量与合并结果

最终口径：

| 展示 | 计算 |
| --- | --- |
| 总共已刷 X 遍 | 普通练习已完成会话数，status/phase 均 completed 且有 completedAt |
| 错题重练已完成 X 轮 | 错题来源的已完成会话数，单独显示，不混入普通练习遍数 |
| 累计已刷 X 题 | 所有来源的有效逐题作答数量，同题重复作答累计；不包含跳过或草稿 |
| 正确 | 自动判对 + 自评记得 |
| 错误 | 自动判错 + 自评没记住 |
| 正确率 | 合并正确 /（合并正确 + 合并错误），四舍五入为整数百分比；无有效作答显示“暂无” |
| 跳过 | 保留独立数量，不进有效题量/正确/错误/正确率分母 |

关键行为：

- 一轮按开始时固定的可练题快照走到结束，即增加一遍；**有跳过、甚至全跳过也增加一遍**。不要求每题有效作答。
- 继续同一轮、重复读取统计不重复增加；仅点开始新一轮不增加；尚未完成或被替换的会话不计完成遍数。
- 100 题答 80、跳 20：完成后增加 1 遍和 80 题；末题停在反馈页但还没有“完成本轮”时不提前加遍数。
- 错题重练可能只覆盖题库子集，因此独立计完成轮数。其有效作答仍进入累计题量和合并正确率；主页小字已说明范围。
- 题库修改不追溯重算已经完成的轮次，也不修改当时作答结果；原历史直接按新展示口径汇总，不要求清空数据。
- 题库主统计、本轮完成页、单题累计合并正确/错误；错题本历史错误数也合并。单条历史记录仍显示真实判题/自评来源，避免把自评伪装成程序判题。
- 简答文字匹配算法仍仅供参考，不参与合并结果的判对。

实现采用只读派生统计：新增轻量 SQL COUNT 查询，按 libraryId/完成状态/来源计数，不解码整个会话题目 JSON，也不以作答数除题库大小推算遍数。历史作答与完成轮数通过同一个 Room 读事务取得，避免完成边界上两者来自不同时间点。题库当前题目仍沿用原先读取路径，用于掌握状态/错题映射。

### 第 6 项：明确的错题本按钮

统计卡底部统一使用与练习按钮相同的浅橙实心 `WarmButton`，宽度填满卡片，文字“打开错题本”。加载、暂无数据和错误状态均保留一个入口，不依赖统计成功才出现。原两处文字式入口合并为这一处。

## 改动文件

以下相对项目根目录，`…` 表示 `android/app/src/main/java/com/offlinequiz/app`。

| 文件 | 改动 |
| --- | --- |
| `…/ui/LibraryDetailScreen.kt` | 移出列表/筛选；增加 ALL 管理入口；展示新汇总；统一实心错题本按钮 |
| `…/ui/QuestionRow.kt`（新增） | 承接原有共享题卡，供管理页使用，逻辑和原文提示保留 |
| `…/ui/AppRouteState.kt` | openQuestionManager 增加 onlyPending 参数，默认仍为需处理，支持显式全部 |
| `…/MainActivity.kt` | 主页 ALL 与 ATTENTION 入口分别接线；移除主页不再使用的编辑/历史回调 |
| `…/model/PracticeStats.kt` | 新 PracticeTotals、PracticeRoundCounts、PracticeStatsSnapshot；保留原始分类统计字段，新增合并展示与完成计数 |
| `…/model/PracticeModels.kt` | 本轮结果新增合并 totals 派生属性 |
| `…/data/PracticeStatsRepository.kt` | 读取作答+完成轮数快照，传给统计计算器 |
| `…/data/PracticeStore.kt` | 增加 libraryStatsSnapshot 契约及 Room 适配 |
| `…/data/QuizDatabase.kt` | 增加 completedRoundCounts 查询、PracticeStatsRows 与 libraryStatsRows 读事务；不改 Entity/迁移 |
| `…/ui/PracticeScreen.kt` | 本轮合并结果/正确率，更新自评统计说明与完成轮数提示 |
| `…/ui/QuestionHistoryScreen.kt` | 单题累计合并统计，保留每条历史的来源 |
| `…/ui/WrongbookScreen.kt` | 历史错误次数合并；掌握状态与错题筛选规则不变 |
| `android/app/build.gradle.kts` | versionCode 19 / versionName 0.4d-management-stats |
| `android/app/src/test/…/PracticeMergedStatsTest.kt`（新增） | 6 项合并/跳过/完成边界/重复作答/跨库隔离/旧历史测试 |
| `android/app/src/test/…/AppRouteStateTest.kt` | 新增 1 项 ALL 与 ATTENTION 不同入口及返回路径回归 |
| `android/app/src/test/…/BackupCoreTest.kt` | 加强既有恢复计划测试，确认恢复会话可直接推导完成遍数 |
| `android/app/src/test/…/TestStores.kt`、`PracticeRepositoryTest.kt`、`PracticeViewModelEntryTest.kt` | 为既有假存储补齐新快照接口，未删除或放宽既有断言 |
| `android/app/src/androidTest/…/PracticeStatsDatabaseTest.kt` | 新增 1 项真实 Room 快照/完成来源/跨库隔离测试（仅编译） |
| `docs/BACKLOG.md`、`PROJECT_BRIEF.md`、`DSH_HANDOFF.md`、`CODEX_HANDOFF.md`、`STATUS.md` | 本轮授权、完成项、口径细化与交接入口 |
| `README.md`、`android/README.md` | 新行为、版本、测试数量及安装包路径 |

未修改解析器、判题算法、练习提交/推进写入流程、题库保存/校正流程、题目与作答快照、备份格式/编解码、包名、签名配置或 04B 品牌素材。Room schema 仍为 3，无数据迁移或持久化计数器。未打开真实用户库，不提交推送或发布。

## 实际验证

- 最终完整强制构建：**BUILD SUCCESSFUL，79/79 任务实际执行**，同时覆盖 JVM、debug APK、设备测试 APK。不是仅“准备好了命令”。
- 最终 JUnit XML：**36 类，259 项，0 失败、0 错误、0 跳过**。在 04C 的 252 项上新增 7 项。
- 新增的真实 Repository 流程实际跑过 100 题：80 次作答、20 次跳过，最后反馈页仍为 0 遍，完成本轮后为 1 遍/80 题，正确率 50%。另验证全跳过也计一遍、错题完成另列、重复查询/新建未完成轮次不多计、历史改题后保留题量/遍数。
- 加强原备份导出→读取→恢复计划测试：原有完成状态能直接计算正常完成遍数，不引入备份新字段。
- **24 项设备测试仅编译、未执行**（原 23 + 新增 1）。adb devices 列表为空，未启动模拟器或安装新工具。
- 原 schema 目录中的 JSON 与修改前副本逐字节一致；包名不变，aapt2 确认名称“题匣”、版本 19，未增加联网/广泛存储权限。
- apksigner 验证成功，与 04C 包签名证书输出一致；实际覆盖安装未执行。
- 最终包无 PDF；旧 04C APK 的 SHA-256 保持原值。精确条目数、文件哈希与差异见 verification.json。
- 源码检查主页已无题目卡片调用/筛选条、错题本入口为 Button，正常题复核标签未重新引入；此类静态检查不代替手机视觉验证。

### 本轮遇到的构建问题及处理

首轮增量构建编译成功，但 259 项中有 12 项回归失败：部分匿名 `PracticeStore by ...` 测试代理仍使用旧字节码，调用新增方法时产生 AbstractMethodError，并导致相关错题保护断言连带失败。

没有修改或放宽这些保护测试；最终关闭 Kotlin/KSP 增量缓存并 --rerun-tasks 全量重编译后，259 项全部通过。期间一次直接传 PowerShell 点号参数被拆成 `.incremental=false` 任务，立即改为字符串参数数组传参，错误日志留档，没有反复重试同一命令。

DSH 如后续修改 PracticeStore 接口遇到类似增量代理问题，可按本轮方式验证（只影响当前命令，不改全局设置）：

```powershell
# 按 android/README 配置项目内 JDK/SDK/Gradle 环境变量后，从项目根目录运行。
$gradleArgs = @('--project-dir','./android','--offline','--no-daemon','--rerun-tasks',
  '-Pkotlin.incremental=false','-Pksp.incremental=false',
  ':app:testDebugUnitTest',':app:assembleDebug',':app:assembleDebugAndroidTest')
& ./android/gradlew.bat @gradleArgs
```

最终仍有既有实验协程 opt-in、SDK XML 等警告，不阻止构建；本轮未为警告修改无关测试或升级依赖。

## 新测试包

`outputs/apk/tixia-04d-stats-debug.apk`

- versionCode **19**，versionName **0.4d-management-stats**。
- **44,152,400 bytes**。
- SHA-256：`7E5B9024664906822F793DCD96F066B8DEAA9B3FD1C5BF2F26C1D2A5B6A36833`。
- 仅本地测试包，所有历史 APK 保留，未发布。

## 尚未验证与阻碍

- 新版实际页面布局、按钮点击、管理页往返、窄屏/大字体、旋转/进程恢复尚未在用户手机操作；导航逻辑有 JVM 覆盖，不等于手机体验通过。
- 24 项设备测试和新 Room COUNT/读事务在真实 Android SQLite 上尚未运行。当前无连接设备。
- 覆盖安装保留真实库数据尚未实机验证；未打开真实用户库。schema/包名/签名一致检查已做，不能据此声称实机升级成功。
- 大题库统计性能尚未实机测量。完成轮数查询不读取会话题目 JSON，但历史作答汇总仍沿用既有完整历史读取。
- 第 5 项全局系统返回手势仍未实施；其余本轮范围外需求不继续推进。
- 当前无构建阻碍；本报告为实施者自检，仍可按用户安排做后续独立评审。

## 手机短检查

1. 打开题库：首页只见主要入口，不再铺题目列表。点“题目管理”看到全部题，点“去校对”看到需处理题，返回回到练习主页。
2. 查看统计：正确/错误已合并，遍数和题量分开；完成一轮（可包含跳过）后遍数增加一次，跳过不增加题量；错题完成轮数另列。
3. “打开错题本”现在为有底色的按钮，可正常进入。

## 留档与交接

- 修改前快照：`work/practice-stats-20261104/before/`（main/test/androidTest、schema、构建配置及被修改既有文档）。
- 完整文本差异：`work/practice-stats-20261104/source-changes.diff`。
- 验证脚本及结果：`verify_changes.py`、`verification.json`，同目录。
- 最终构建日志：`final-build.log`；首轮失败 `build.log`、PowerShell 参数错误 `command-parse-failure.log` 原样保留。
- 包与设备检查：`apk-badging.txt`、`signature-new.txt`、`signature-previous.txt`、`devices.txt`。
- 题卡迁移辅助脚本 `split_question_row.py` 仅用于本轮，不需要重复执行。
- DSH 接手应先看本报告及 DSH_HANDOFF 顶部，不恢复旧的分开统计或主页长列表，不把错题子集完成轮数混入普通遍数，不改变“跳过仍可计一遍”规则。

本阶段实施与自检完成后停止，等待手机反馈或后续评审。
