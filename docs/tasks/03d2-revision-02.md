# 阶段 03D2 返工 02：就绪预览取消与导出关闭错误

2026-11-03 用户既有授权验收后直接下达。确认F:\New project，读AGENTS、PROJECT_BRIEF、DSH_HANDOFF和docs/reports/03d2-r1-codex-review.md。只收尾本轮两项问题，不进入发布。

## 修复与验证

1. prepare完成后active已清空，但cancelPreview必须仍能取消prepared预览：作废确认授权、释放本请求临时ZIP、返回Idle。不能因无活动读取Job直接return。取消后confirm不得调用commit，重复取消幂等；取消后可重新准备/导出，旧回调不能清理新预览。保留Committing期间拒绝取消与原请求身份保护。
2. 成功导出必须包含目标流成功关闭；write/flush成功但close失败也应显示“导出未完成”，不进入Success。成功路径不能用吞异常的closeQuietly；异常后的兜底清理可安全关闭，但不得掩盖原错误或重复发布。检查打开目标与句柄交付时发生取消时的所有权，句柄即使未返回主线程也应正确关闭，不留下泄漏。保留生成成功后才开目标及逐块取消。

新增正式控制器测试覆盖：读取完成且Job已结束的Preview取消、取消后确认无提交、重复取消/重新准备、关闭异常不报成功、正常关闭成功只发布一次。补句柄打开交付时取消的清理回归，核查UI预览按钮及所有现有入口实际接入。

## 交付

- 原样执行work/codex-review-03d2-r1/tests/BackupControllerReviewTest.kt两项；同目录reviewer.init.gradle含原7项（共9项），不要改验收断言/任务/记录。
- 全部正式JVM、原9项独立、共享解析、assembleDebug/assembleDebugAndroidTest实际执行；22项或新增仪器测试无设备仅编译并明确未执行。
- 保持Room事务/ABORT、新副本/原数据/PDF、原取消令牌和其他已验收规则；不打开真实用户库试恢复、不改项目外设置/凭据、不发布提交推送。
- 新APK outputs/apk/offlinequiz-03d2-r2-debug.apk，versionCode>15、原applicationId/签名，保留全部旧包，无INTERNET/广泛存储/真实样本。
- 报告 docs/reports/03d2-r2-dsh-report.md，更新STATUS为PHASE_03D2_R2_READY_FOR_REVIEW或准确阻碍及交接/设备清单（如必要），区分实际验证/未验证。
- 完成即停止等用户叫Codex验收，不自动推进发布准备。
