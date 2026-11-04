# 阶段 03B R1 独立验收

日期：2026-11-03。结论：**CHANGES_REQUESTED**。原三项问题已补齐并通过对应复核，但确认新建会话的取消路径仍有一项可复现缺陷。本轮不下达新任务，等待用户安排。

## 已复核的修复

- 原独立 `WrongbookConflictReviewTest` 原样执行通过：活动会话首个查询未完成时拒绝新建。UI 与 ViewModel 四态保护、失败重试、存在会话时的明确确认路径均已接入。
- 全量题目列表每行已经有“练习记录”，未练/仅正确/仅自评/仅跳过不再依赖错题身份；AppRouteState 保存历史来源，题库/错题本→历史→校对→历史的返回规则有正式回归；历史及错题本接入系统 BackHandler。
- 历史卡实际使用 HistorySnapshotView，只从记录的 referenceSnapshot 展示旧题干/选项/参考答案，可展开；当前题目与历史标识分开，缺失快照明确提示。

## [P2] 确认查询挂起期间取消，查询返回无会话时仍新建

文件：`viewmodel/WrongbookViewModel.kt` 的 `confirmStartPractice`（178行起）、`cancelPendingStart`（213行起）；`ui/WrongbookScreen.kt` 的确认弹窗（266行起）。

复现顺序：

1. 界面已知存在旧活动会话，用户点重练，再点“确认开始新一轮”。
2. 方法捕获 mode/libraryId，等待 activeSession 重新查询，此时 starting=true。
3. 弹窗“取消”“继续现有练习”及 onDismissRequest 仍可触发 cancelPendingStart；它仅把 pendingStartMode/pendingExpectedActiveId 清空，并未停止或作废本次协程。
4. 延迟查询返回 null（此前观察到的会话已经结束/不再活动）。fresh?.id 和被取消清空的 pendingExpectedActiveId 同为 null，分支跳过，继续调用 createRound。
5. 用户已经取消，但仍创建新重练并设置 startedPracticeId，界面会随之跳转。

独立 JVM 测试 `work/codex-review-03b-r1/tests/WrongbookCancelReviewTest.kt` 直接驱动实际 ViewModel/Repository，以可控挂起的活动会话复查返回 null 模拟这一状态变化，实际结果：

```text
REVIEW canceled confirmation: new=true started=new
Cancel during pending read must prevent creating a replacement session
```

测试成功编译执行，业务断言失败，退出码1。与原独立冲突测试一起执行：原测试通过，新取消测试失败。对照用例中查询仍返回原活动会话时未创建，缺陷范围明确为“取消期间延迟查询发现无活动会话”，并非每次取消都会失败。使用内存Store，未声称真实Room/手机触控已复现。

建议：在实际写入开始前取消应作废本次确认请求（任务句柄/令牌等），挂起返回后检查是否仍被授权继续；如果实现选择在提交开始后不允许取消，则所有取消/继续/关闭入口必须统一禁用或拦截，并明确保存中状态，不能让UI接受取消但后台继续。补挂起复查返回 null/原会话/不同会话的取消回归。此项仍属原任务“取消不产生写入”的验收范围。

## 实际验证

- 确认目录并读取需求/交接/返工任务/报告；检查三项修复代码和测试，未改产品实现。
- 离线强制重建 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`：**BUILD SUCCESSFUL，79任务全部执行**；正式 XML 汇总 **142 tests / 0 failures / 0 errors**。
- 共享PDF回归独立重跑：**30 PASS / 0 FAIL**，含201题样本。
- 原冲突复现未改；新增验收测试只通过 work 下 init 脚本临时加入测试源。日志在 `work/codex-review-03b-r1/`：build.log、unit-summary.txt、parser-tests.log、review-tests.log、cancel-test-result.xml、original-conflict-result.xml。Gradle默认测试结果最后显示额外测试，142项正式成功来自此前完整强制构建。
- 独立重建与交付APK SHA-256一致：`F6E6B21C3489A44E6134CDD96E95342F21EC44B612C230A6FF8BA1E55CB97884`；versionCode9 / `0.3b-r1-wrongbook`；apksigner验证通过、原调试签名沿用；aapt2权限检查无INTERNET/存储权限。

## 未验证与停止边界

- 无设备，19项仪器测试仅编译未运行，真实迁移/事务/持久化/界面操作与进程恢复仍待手机验证。
- 未提交、推送、发布，未给DSH发消息，未进入下一阶段。等待用户安排剩余返工。
