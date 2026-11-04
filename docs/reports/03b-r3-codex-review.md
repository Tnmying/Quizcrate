# 阶段 03B R3 独立验收

日期：2026-11-03。结论：**PASS（代码/逻辑/构建；真机待测）**。提交期间离页入口遗漏已修复，本轮未发现需阻止进入下一开发阶段的问题。用户明确授权通过后直接安排下一步，任务为 docs/tasks/03c-short-answer-match.md。

## 修复复核

- WrongbookViewModel.tryLeaveScreen 在提交中拒绝离页并提示；复查中先作废请求再放行；空闲放行。沿用R2令牌、作业取消及挂起后校验。
- WrongbookScreen 顶部返回、系统返回、错误态返回、去校对、题目行历史/校对、弹窗继续原练习均接入守卫。题目行按钮另按starting/committing禁用，提交阶段模态等待拦截页面触控；页面继续原练习按钮在starting时禁用。
- 成功后的startedPracticeId消费及自动导航仍单独保留，未被离页守卫拦截。失败/完成时解除committing，恢复交互。源码接线与5项新增守卫回归已检查。

## Codex实际验证

- 确认 F:\New project，读取需求/交接/任务/报告并核查源代码及测试；未改产品代码。
- 离线执行 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest：BUILD SUCCESSFUL，79任务均UP-TO-DATE；本轮是增量构建检查，未称独立强制重建。
- 随后用 :app:testDebugUnitTest --rerun 强制执行测试任务：正式24类 **154 tests / 0 failures / 0 errors**，1任务执行、28任务UP-TO-DATE。日志formal-tests.log，汇总unit-summary.txt。
- 既有WrongbookConflictReviewTest与WrongbookCancelReviewTest原样独立复跑：**2 tests / 0 failures / 0 errors**，日志review-tests.log及XML。通过work下init临时加入测试源，未改验收断言。
- 共享解析回归实际重跑：**30 PASS / 0 FAIL**，含201题样本，日志parser-tests.log。
- 上述日志位于 work/codex-review-03b-r3/；默认Gradle测试结果最后是独立测试，154项正式汇总来自前次全量实际执行。
- 交付APK与当前构建产物SHA一致：856761868759A0269D5294F7EFDACE152FBF79ECE14A09D4C84825E2E9AC421E；versionCode11、0.3b-r3-wrongbook；apksigner通过且原调试证书沿用；aapt2仅列应用内部权限，无INTERNET/存储权限。此处为产物比对，非本轮强制重建的字节复现声明。

## 未验证与下一步

无安卓设备；19项仪器测试只编译未执行，真实Room迁移/持久化、Compose触控、系统手势、旋转/强杀恢复仍需设备验证。不能将本次通过等同于发布验收。

按用户授权下达03C简答离线文字匹配辅助，完成后停止等待用户通知验收。不进入备份或发布，不提交推送。
