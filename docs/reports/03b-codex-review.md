# 阶段 03B 独立验收

日期：2026-11-03。结论：**CHANGES_REQUESTED**。统计聚合与多数练习逻辑已完成，但存在会话冲突保护缺陷和每题历史功能缺口。本轮只记录验收结果，未下达返工或下一阶段任务。

## 需要修复

### [P2] 现有会话尚未读出时可绕过确认创建重练

- `ui/WrongbookScreen.kt:192` / `203` 只判断 `activePractice != null` 决定是否弹冲突框；两个开始按钮（198/209 行）只依赖待复习数量和 starting，未依赖 `practiceLoaded`。
- `WrongbookViewModel` 并行读取统计和活动会话：统计可能先完成，此时已有可练错题，按钮可用，但活动会话 Flow 还没有首个值。点击就直接 startPractice，未询问用户。
- `observeActivePractice` 的异常分支还把查询失败当成 `activePractice=null, practiceLoaded=true`，同样不能据此证明没有现有会话。
- 独立 JVM 复现直接驱动实际 WrongbookViewModel/PracticeRepository，暂时挂起活动会话首个返回，统计读取正常，再调用与真实按钮一致的 startPractice：**未得到首个活动会话结果就创建了新会话**，测试明确业务断言失败，1 test / 1 failure，退出码1。
- 复现用内存 Store，仅证明绕过保护并创建新会话；真实生产 `RoomPracticeStore.createSession → createSessionWithSupersede` 会在事务中把旧活动会话改为 superseded，旧历史并不删除，但原未完成练习会失去“继续”入口。未声称真实数据库/界面已运行复现。
- 建议把“未知/读取失败/确实没有会话”分开；首个成功读取前禁用开始，失败可重试。ViewModel/开始动作也应有保护，不只靠 UI；已有会话只能通过明确继续/确认新建的路径处理。

### [P2] 非错题无法访问每题次数与历史

- `MainActivity.kt:105` 的 history 路由入口仅来自 WrongbookScreen。
- `LibraryDetailScreen.kt:250` 的所有题目行点击仍然只打开编辑页；编辑页也无历史入口。
- WrongbookScreen 仅展示 due 或 wrongbook，两个集合都限定为“曾错题”。因此一题始终答对、仅自评记得、只跳过或尚未练习时，用户无法查看这道题的次数/历史。仓库支持按题查询，不代表用户界面已交付“每题详情”。
- 建议从全量题目列表或题目编辑/详情提供稳定的“练习记录”入口，保留对应返回目的地，覆盖无历史、只有正确/自评/跳过的情况。

### [P2] 旧记录未展示当时题干及选项

- `QuestionHistoryScreen.kt:109` 头部展示当前题干；`HistoryRecordCard`（174行起）仅显示时间、用户答案、当时参考答案和版本提示。
- 虽然 referenceSnapshot 保存了旧 stem/options，但卡片没有展示它们。题干/选项改过后，只看到“用户A、正确A”及“旧版本”提示，无法核对当时回答的是哪道题、A代表什么。
- 任务要求历史按当时快照展示，不能用新版内容充当历史；建议历史卡可展开完整当时题干/选项/参考答案，缺快照时给明确说明。此项和上一项均为界面/路由源码核对，未做真机点击。

## 实际验证

- 确认目录并读取需求/交接/任务/报告，检查聚合器、版本匹配、只读仓库、历史 JOIN 查询、错题重练候选过滤、ViewModel、导航及界面。
- 独立离线强制重建 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`：**BUILD SUCCESSFUL，79任务全部执行**；正式 XML 汇总 **115 tests / 0 failures / 0 errors**。
- 独立共享解析回归：**30 PASS / 0 FAIL**，含201题样本。
- 新增验收专用 `work/codex-review-03b/tests/WrongbookConflictReviewTest.kt`，通过 work 下 init 脚本临时加入测试源，实际编译执行失败；未改正式 src/Gradle 配置。结果与日志：`conflict-test-result.xml` / `conflict-test.log`；原全量成功记录见 `unit-summary.txt` / `build.log`。Gradle默认测试报告最后显示这项额外失败测试，不应与此前115项正式测试结果混淆。
- 交付 APK 与独立重建 SHA-256 一致：`289576A5FF9FC6CBCF6BB1D7474EF99159180DE03A7802D6CD45443583E124AB`；versionCode8 / versionName `0.3b-wrongbook`，签名沿用；aapt2检查无 INTERNET/存储权限。

## 尚未验证与边界

- 19项仪器测试仅编译未运行：真实 Room JOIN/排序、v1→v3/v2→v3迁移、持久化未做设备实测。静态检查发现显式增列/索引迁移及旧路线保留，但不能把构建成功当作迁移通过。
- 无安卓设备，Compose真实触控/系统返回、强杀恢复、实际大量历史性能仍待测。
- 未改产品实现、未提交推送发布、未给DSH发新任务。等待用户后续安排。
