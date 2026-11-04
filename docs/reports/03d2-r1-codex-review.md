# 阶段 03D2 R1 独立验收

日期：2026-11-03。结论：**CHANGES_REQUESTED**。原分块取消/目标延迟打开/请求身份修复已接入，原7项独立测试通过；新控制器还存在两项可复现缺陷。用户既有授权直接下达收尾，任务 docs/tasks/03d2-revision-02.md。

## [P2] 准备完成的预览不能通过“取消”作废

BackupController.prepare成功把PreparedRestore保存在prepared并进入Preview，finally的finish(request)随后清空active。cancelPreview在211行先执行active ?: return，因此预览就绪后正常点击“取消”直接返回，不释放预览、不回到Idle。BackupScreen预览取消按钮实际调用此方法。

独立测试驱动实际控制器：prepare完成→cancelPreview→confirm。取消后仍是Preview，PreparedRestore.cancelled=false，随后confirm仍调用commit一次。这里是注入服务的调用计数，未写真实DB。

## [P2] 目标流close失败仍显示导出成功

BackupController.export在96行调用closeQuietly(target)，该辅助函数吞掉所有close异常；紧接着进入Success。某些输出流/DocumentProvider在关闭阶段才能报告最终写入失败，这样会误报备份完成。

独立测试注入write/flush成功、close抛IOException的输出流，实际状态仍为Success。成功完成路径应将关闭异常当作导出未完成；仅在已处理失败后的兜底清理中才可静默关闭。

两项测试位于 work/codex-review-03d2-r1/tests/BackupControllerReviewTest.kt，成功编译运行后业务断言均失败。

## 已复核与实际执行

- 确认目录F:\New project，读需求/交接/任务/报告并检查BackupController/Operations/Service/VM及正式控制器测试；未改产品实现。
- 服务拆prepareExport/publishExport，VM只在准备校验成功后打开SAF目标，复制逐块取消；每请求独立AtomicBoolean和身份检查，临时Prepared结果交付回调及committed判断均已实际接线。
- 离线 :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest 成功，正式34类 **232 tests / 0 failures / 0 errors**；79任务中7执行/72 UP-TO-DATE，测试实际重跑，构建为增量检查。
- 原7项独立测试+新增2项实际执行 **9 tests / 2 failures**：原7通过、新2失败。原导出取消测试已从取消后5块变为0块；既有断言未改，新增通过work下init临时加入。
- 共享解析实际 **30 PASS / 0 FAIL**，含201题样本。
- work/codex-review-03d2-r1/ 保存build.log、unit-summary.txt、review-tests.log、parser-tests.log及独立XML。232项汇总来自先前完整正式执行，默认Gradle结果最后为额外测试。
- APK与当前构建产物SHA一致：CEA3E7BD4F8DEE55E06ABD7A92D56F437FFEEB8E7B354DFDEF632C8CC38C6E06；versionCode15/0.3d2-r1-mobile-backup，原调试签名验证通过；aapt2仅列内部权限，无INTERNET/广泛存储。

## 限制与后续

22项仪器测试仅编译未执行，真实Room/SAF/触控/旋转/强杀仍待设备。独立控制器测试采用注入服务与输出流，不声称手机触控或真实DB已复现。直接下达03D2 R2，集中收尾，不进入发布准备，不提交推送发布。
