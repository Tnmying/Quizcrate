# 阶段 03D1 R1 独立验收

日期：2026-11-03。结论：**PASS（代码/逻辑/构建；手机接入与真机待测）**。两项返工已修复，可进入03D2开发。用户既有授权验收后直接下指令，任务见 docs/tasks/03d2-mobile-backup.md。

## 修复复核

- BackupValidator按answer.position索引session.questions，再核对作答题目及当时历史快照，已去掉“题库位置必须等于本轮索引”的错误。非空历史快照需等于会话当时快照，null旧快照保持兼容。
- 校验每个会话的作答位置集合：answering严格为已完成前缀、feedback含当前题、completed覆盖全轮；superseded按原phase处理。缺失/断档/超前记录明确拒绝，不制造数据或重置进度。
- 新正式测试通过真实PracticeRepository生成错题子集及随机整轮，实际往返读写/新ID计划，核对保留顺序与原题库位置；正反例覆盖阶段、缺失快照、内容不一致等。
- 两项原独立业务复现未改，均由失败转为通过，未扩大到生产手机写库。

## Codex实际执行

- 确认F:\New project，阅读需求/交接/任务/报告并核查改动及正式测试，未改产品代码。
- 离线 :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest 成功；30类 **218 tests / 0 failures / 0 errors**。测试实际重跑，79任务中1执行/78 UP-TO-DATE，构建为增量检查，不称本轮强制重建。
- 既有6项独立测试通过work下init临时加入测试源，原样实际运行 **6 tests / 0 failures / 0 errors**，含本轮2项备份位置/完整进度测试，未改断言。
- 共享解析实际 **30 PASS / 0 FAIL**，含201题样本。
- work/codex-review-03d1-r1/ 保存build.log、unit-summary.txt、review-tests.log、parser-tests.log和独立XML。默认Gradle结果最后为额外测试，218项正式汇总来自此前完整运行。

## 未接入/未验证与下一步

本轮无版本升级或新交付APK，最新可用仍03C R1。核心测试使用内存Store/合成数据；19项仪器测试仅编译未执行。真实Room一致性读取、事务写库/回滚、PDF资源发布、系统文件选择与手机触控/进程恢复均未实现或未验证，本次通过不代表手机备份已可用。

按授权进入03D2接入上述手机流程与一致性边界，仍不提交推送或发布；完成后停止待用户叫验收。
