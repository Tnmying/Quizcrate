# 阶段 03D2 R2 独立验收

日期：2026-11-03。结论：**PASS（代码/逻辑/构建；真机待测）**。本轮两项缺陷及打开句柄时取消的资源清理已修复，原独立复现全部通过。按用户既有授权，下一任务 docs/tasks/04a-device-acceptance-kit.md，只准备验收材料/说明，不发布。

## 修复复核

- cancelPreview不再依赖active存在，读取Job结束后仍作废prepared、清理临时文件并回Idle；取消后confirm无提交，重复取消幂等。保留提交中拒绝取消和新旧请求隔离。
- 导出显式等待目标成功close再报告Success，close异常进入“导出未完成”；closeQuietly仅用于异常/取消后的兜底。正常成功只关闭/发布一次。
- 打开目标在IO+NonCancellable区域登记AtomicReference，返回交付时取消也能由finally关闭；实际接入控制器的正式回归覆盖此边界。UI仍通过VM适配器使用该控制器。

## Codex实际验证

- 确认F:\New project，阅读需求/交接/任务/报告并核查控制器、服务、VM和正式测试；未改产品实现。
- 离线 :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest 成功：34类 **236 tests / 0 failures / 0 errors**；79任务中7执行/72 UP-TO-DATE，正式测试实际重跑，构建为增量检查。
- 原9项独立测试用work下init临时加入测试源，原样实际执行 **9 tests / 0 failures / 0 errors**，包括上次失败的就绪预览取消与close异常，以及分块取消、备份位置/进度、数值、冲突/取消。未改既有断言。
- 共享解析实际 **30 PASS / 0 FAIL**，含201题样本。
- 日志/结果存 work/codex-review-03d2-r2/：build.log、unit-summary.txt、review-tests.log、parser-tests.log及独立XML。236项正式汇总来自此前完整执行，默认结果最后为额外测试。
- APK与当前构建产物SHA一致：620165237995BCDA53824B9E4C4681ABE2EBA40E30032F7E2DC2A308E78ED468；versionCode16/0.3d2-r2-mobile-backup；apksigner验证通过，原调试证书；aapt2仅列内部权限，无INTERNET/广泛存储权限。

## 尚未验证

本轮22项仪器测试仅编译未运行；真实Room、系统DocumentProvider、触控、旋转/强杀和低存储未设备验证。独立测试使用注入服务/输出流，不能等同手机实测。仅debug包，不能视为发布验收。

下一阶段04A准备自制PDF/期望结果、约10分钟首轮手机清单、最新包校验信息和准确项目说明。不给用户虚构测试结果、不替用户决定正式名称/许可证、不提交推送发布。
