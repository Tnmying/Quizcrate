# 04C：校对状态、刷题按需纠错及直达需处理题

日期：2026-11-04。实施者：Codex。用户明确要求 Codex 亲自实施此前汇总的第 1、2 项，并记录交接。本报告是实施与自检记录，不是独立评审结论。

## 最新需求与范围

本次实施过程中用户补充：“未人工复核这个标签删掉，默认不是待处理的题就是没问题的题，有问题通过刷题页反馈”。最终界面按这条补充落地：

- 正常题直接使用，不显示“未人工复核”或“已人工确认”状态标签，不展示相关数量/筛选；筛选只保留“全部”和“需处理”。不额外标记“已验证正确”，不自动改写人工确认事实。
- 只有识别有疑问、缺答案、结构不完整、当前不支持等确实不能正常练习的题显示需处理提示，保留具体原因。
- 刷题页提供“纠错/校正本题”，进入题库中该题的编辑页并可保存、确认。
- “去校对待处理题”直接打开独立列表，预选需处理，从顶部显示筛选与首题；处理完毕显示明确空状态。

第 2 项依赖独立目的页，因此新增了最小题目管理页面。但第 3 项的全面页面拆分未实施，题库主页原有完整题目列表仍保留。统计口径、全局系统返回手势、错题本按钮等其他待办继续暂缓；DSH 无新任务。不提交、推送或发布。

## 已完成的行为

### 1. 需处理语义统一

新增 `QuestionReview`，直接复用现有 `PracticeEligibility.classify`：结果不是 ELIGIBLE 才是需处理。首页汇总、题库详情的计数/筛选/题卡、独立列表、编辑页状态和恢复摘要使用同一口径。

- 正常自动识别题即使 `confirmed=false`，也不计入需处理，仍可练习。
- 未确认的原始识别疑问仍需处理；用户明确确认后可解除该类疑问，原始证据不丢弃。
- 缺答案、结构错误和不支持题型仍按既有练习资格规则拦截，即使 `confirmed=true` 也不能绕过结构检查。
- 需处理数量是上述问题题的并集，一个题只计一次；“人工是否确认”与“能否练习”不再互相冒充。
- 首页原 SQL 只统计 `!confirmed`，不能覆盖当前题目结构错误。现改为 Room 事务 relation 读取题目、使用统一规则计算汇总，JSON 解码/分类放在 Default dispatcher；只改查询和派生数据，不新增表/列。
- `LibrarySummary.confirmedCount` 改为显式真实计数，不再用总数减需处理数推断。该内部数据不作为复核标签展示。
- 恢复预览的 `pendingReviewQuestions` 保留字段名，仅更正派生计数，不改备份文件格式或恢复计划中的题目事实。

### 2. 刷题时按需纠错

刷题页右上方新增“纠错/校正本题”。

- 当前尚未提交时，先取消延迟草稿写入并保存当前输入，成功后再进入编辑页；失败留在刷题页，保留输入并提示，可重试。
- 正在提交、前进或保存离页草稿时，不能进入纠错。顺便修正原 `requestExit` 在反馈阶段可能先导航、再检查 saving 的顺序，所有阶段先检查在途操作。
- 已提交题也可打开源题校正，但不重新提交、不重判原记录。
- 编辑源题的现有验证、仅保存/保存并确认、原页对照、保存中保护、未保存离页确认均保留。没有自动确认整库，也没有改写原始题文/证据。
- 保存修改的是题库当前版本；已有轮次在开始时固定题目，所以**修改用于之后新建的轮次，当前轮次继续使用原题和原答案**。编辑页明确说明这一范围，并提示返回可继续原轮次或回题库另开新一轮。
- 保留既有作答、判题结果、历史快照和原练习进度。纠错本身不产生一次答题，不重置一轮，也不伪造掌握状态。
- 通过导航状态保存练习会话 ID、返回目的地和管理页筛选；普通练习与错题练习纠错后均回原会话。

### 3. 校对入口直接看到需处理题

新增 `QuestionManagerScreen` 和 QUESTIONS 路由。

- 从练习入口卡点击“去校对待处理题”直接进入该页，而非仅修改主页下方筛选。
- 入口覆盖识别疑问、结构异常及不支持题型，不再只在识别疑问题数量大于零时出现。
- 默认 ATTENTION 筛选，按既有题库顺序展示。进入页面、切换筛选及再次点当前筛选均回到列表顶部。
- 可进入编辑或题目历史，页面内返回回到相应目的地，筛选保留；编辑保存后跟随题目 Flow 更新，已解决题从需处理列表消失，最后一题处理完显示“没有需要处理的题目，可以返回题库继续练习”。
- 新管理页接入自己的系统返回处理；全局其他页面的手势返回待办没有在本次一并实施。
- 导航持久化从 7 字段扩为 8 字段，兼容旧 7 字段状态，恢复时缺少新字段使用 ALL；新入口总是预选 ATTENTION。

## 改动文件（供大肥鱼接手）

路径相对项目根目录，`…` 表示 `android/app/src/main/java/com/offlinequiz/app`。

| 文件 | 改动 |
| --- | --- |
| `…/model/QuestionReview.kt`（新增） | 统一需处理判断、显示标签、汇总和全部/需处理筛选 |
| `…/model/QuizModels.kt` | LibrarySummary 显式传入真实 confirmedCount；内部保留 unreviewedCount，不向 UI 展示 |
| `…/data/QuizDatabase.kt` | LibrarySummaryRow 改为 library + questions relation；observeSummaries 用事务读，未改 Entity/schema |
| `…/data/LibraryStore.kt` | 首页汇总转为统一分类，后台计算 |
| `…/backup/RestorePlanner.kt` | 恢复预览按实际需处理题计数 |
| `…/ui/HomeScreen.kt` | 首页需处理数量；移除人工确认/未复核标签与计数 |
| `…/ui/LibraryDetailScreen.kt` | 统一计数、筛选、需处理原因；共享 QuestionRow；校对入口导航到新页；保留主页原题目列表 |
| `…/ui/QuestionManagerScreen.kt`（新增） | 独立列表、默认需处理、顶部定位、空状态、编辑/历史入口、返回 |
| `…/ui/AppRouteState.kt` | QUESTIONS 路由、管理筛选持久化、旧导航状态兼容 |
| `…/MainActivity.kt` | 新页面接线、编辑返回管理页/练习页 |
| `…/ui/PracticeScreen.kt` | 纠错入口，复用 ViewModel 的离页草稿保护 |
| `…/viewmodel/PracticeViewModel.kt` | requestCorrection；在所有阶段先检查 saving/exiting，再处理离页 |
| `…/ui/QuestionEditScreen.kt` | 从练习进入时说明生效范围；正常题不渲染状态标签，需处理题仍提示 |
| `…/ui/BackupScreen.kt` | 恢复摘要文案改为“需处理” |
| `android/app/build.gradle.kts` | 版本 18 / `0.4c-review-correction` |
| `android/app/src/test/…/QuestionReviewTest.kt`（新增） | 6 个分类、计数、正常题无标签、空筛选回归 |
| `android/app/src/test/…/AppRouteStateTest.kt` | 新增 3 个导航/恢复测试，更新持久化字段数断言 |
| `android/app/src/test/…/PracticeViewModelEntryTest.kt` | 新增 4 个纠错草稿成功/失败、重复点击与提交/前进保护测试 |
| `android/app/src/test/…/PracticeRepositoryTest.kt` | 新增 2 个真实 Repository 源题校正、旧轮次/结果隔离、新轮次生效和不批量确认测试；假存储汇总用统一规则 |
| `android/app/src/test/…/LibraryRepositoryTest.kt` | 假存储汇总用统一规则，既有保存/失败测试保留 |
| `android/app/src/test/…/BackupCoreTest.kt` | 新增 1 个导出→读取→恢复计划的 7 题/仅 1 题需处理回归，保留未人工确认事实 |
| `android/app/src/test/…/BackupServiceIntegrationTest.kt` | 更新实际需处理数量预期，并明确断言问题题 ID，说明见下文 |
| `android/app/src/androidTest/…/QuizDatabaseTest.kt` | 用真实 RoomLibraryStore 验证新汇总；正常未确认题不计问题；新增 1 项识别疑问/已确认但结构错误的汇总测试 |
| `docs/BACKLOG.md`、`PROJECT_BRIEF.md`、`DSH_HANDOFF.md`、`CODEX_HANDOFF.md`、`STATUS.md` | 最新授权、用户中途补充、实施范围及交接指针 |
| `README.md`、`android/README.md` | 当前行为、使用流程、版本/测试/新 APK 路径 |

本次没有修改解析器、判题算法、统计计算、LibraryRepository 保存规则、数据库迁移、备份编解码协议、包名、签名配置、名称/图标或 04B 暖色方案。没有打开真实用户库试恢复。

## 实际验证

- 最终一次用项目内现有依赖离线执行：`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`，**BUILD SUCCESSFUL，79 个任务（12 执行、67 已是最新）**。日志 `work/review-flow-20261104/final-all-checks.log`。
- 最终 JUnit XML：**35 类，252 项，0 失败、0 错误、0 跳过**，原 236 项基础上新增 16 项。
- 设备测试 **23 项仅编译、未执行**（原 22 + 新增 1）；adb devices 设备列表为空，未启动模拟器或安装新工具。
- 首轮曾有一个旧备份服务测试失败：旧预期按未确认题数为 1，新规则实际为 2。核对发现该合成样例 q1 虽已确认，却只有一个选择题选项，另 q6 有未解决识别疑问；两题都应需处理。保留样例不掩盖结构问题，将断言明确改为 ID 集合 `{q1,q6}` 与数量 2，随后全部通过。这是本次计数口径更正的预期变化。
- Room schema 3 JSON 与修改前 SHA-256 一致；没有迁移或表/列变化。
- aapt2 检查最终包：`com.offlinequiz.app`、应用名“题匣”、版本 18；权限未增加。
- apksigner 校验最终包成功，与 04B 包的证书输出一致；实际覆盖安装未执行。
- 最终包检查无 PDF，未加入真实样本或完整题库。最终精确条目数、文件差异和哈希见 `verification.json`。
- 界面源码检查不含“未人工复核”文案；正常题的状态 Text 组件也不渲染，而非仅使用透明颜色隐藏。
- 构建存在此前的 SDK XML 版本提示，不影响任务通过；未升级 SDK 或全局环境。

## 安装包

`outputs/apk/tixia-04c-review-debug.apk`

- `versionCode=18`，`versionName=0.4c-review-correction`。
- 大小：**45,124,173 bytes**。
- SHA-256：`47C80ACB29D80369AEA7BB549E41D534F91764E1C271A0785994FD44557D81FD`。
- 所有历史 APK 保留，未覆盖 04B 或更早版本，未公开发布。

初轮构建与中间版本日志仅作过程留档，最终包以 `final-all-checks.log` 和上述哈希为准。

## 尚未验证与阻碍

- 新版的实际点击、窄屏/大字体布局、系统手势、旋转和进程恢复尚未真机操作；导航恢复与保存边界已用 JVM 逻辑测试，不等同手机验证。
- 23 项设备测试、真实 Room relation 查询的设备运行、覆盖安装保留数据尚未验证；当前没有连接设备。
- 首页现在按真实题目内容后台计算需处理数，超大题库的耗时/内存尚未手机实测。
- 当前轮次不会立刻换成修改后的参考答案；这是保留固定快照/历史的明确行为，编辑页已说明。需要立即使用新答案时，应回题库新建一轮。
- 不支持题型仍保持拦截，不因点确认而放开；本次没有增加题型转换功能。
- 第 3 项全面页面拆分、统计、全局返回手势和错题本按钮仍未完成。
- 无构建阻碍；手机验证受限于未连接设备。没有把自检或编译写成独立验收/实机通过。

## 手机最短检查

1. 用示例或已有库看首页/详情：正常题无复核标签，只有真问题计入需处理；点“去校对待处理题”后立即看到筛选和首题。
2. 校正并确认最后一道问题题，返回后应显示无需处理题；重新进入不应夹带普通题。
3. 刷题时先选一个答案，再点纠错；编辑保存后返回本轮，输入/进度仍在。回题库新建轮次才使用修改后的答案；旧结果不改变。

## 交接与留档

- 改动前快照：`work/review-flow-20261104/before/`，包含当时 main/test/androidTest 源码及被修改的既有文档。
- 文本差异：`work/review-flow-20261104/source-changes.diff`。
- 哈希、测试汇总、schema/包检查：`work/review-flow-20261104/verification.json`。
- 检查脚本：`work/review-flow-20261104/verify_review_flow.py`。
- 最终构建：`final-all-checks.log`；其余构建、badging、签名和设备列表日志同目录保留。
- 大肥鱼接手时先读本报告、DSH_HANDOFF 顶部和 BACKLOG 最新状态；不要重新引入“未人工复核”标签，不要再按 `!confirmed` 算需处理，不要让源题校正暗改已创建轮次及历史。

本轮完成后停止，等待手机反馈或后续评审，不启动后续阶段。
