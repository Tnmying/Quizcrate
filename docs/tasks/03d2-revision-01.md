# 阶段 03D2 返工 01：取消、请求隔离及导出发布时序

2026-11-03 用户既有授权验收后直接下指令。确认F:\New project，读AGENTS、PROJECT_BRIEF、DSH_HANDOFF、docs/reports/03d2-codex-review.md。仅收尾本阶段，不进入发布准备。

## 导出发布与取消

- 临时ZIP生成/回读完整校验成功后，才打开系统目标OutputStream并开始复制；当前VM提前openOutputStream必须调整。可以拆分准备/发布或使用延迟打开流的接口，确保生成失败不调用打开目标。
- 最终流复制分块检查取消，用户取消/离页后不能继续写下一块；生成/回读/复制均响应同一请求身份的取消。保留CancellationException，不包装成普通IO失败；目标已部分写入时明确未完成，不显示成功、不误删用户旧文件。
- 原样复跑 work/codex-review-03d2/tests/BackupExportCancelReviewTest.kt；init同目录含原6项独立测试，不改断言。

## 所有备份请求的身份与资源所有权

- export/prepare/confirm及其成功/catch/finally采用独立请求身份，取消A后再开始B，A的迟到返回/异常/清理不得更新B的state、cancel状态或资源。取消标记是该请求独立且线程可见的状态，不复用一个可被后续请求清零的Boolean。
- 取消准备时即作废请求；若服务已产生临时文件/PreparedRestore但结果因调度取消未交给VM，也要正确清理本次资源。不能只改UI状态而留后台任务继续。
- 实际不可取消提交期间保留统一离页保护。DB提交后迟到的真实Job取消不得误报“未写入任何数据”、重复插入或删除已引用PDF；以提交结果/标志为准处理，不能只测试事后顺序调用service.cancel。
- 补可注入服务/URI流边界或真实接入UI的可测试控制器，保留正常Application构造入口，便于实际ViewModel状态测试；不引入大型框架。

## 回归与交付

- 新正式回归至少覆盖：A挂起→取消→B挂起→A迟到（成功/失败/取消）不影响B；导出取消复制；生成/校验失败不开目标；准备结果交付时取消的临时资源清理；确认重复点击；实际提交中Job取消/完成后迟到取消；提交失败恢复操作能力。用可控挂起，不依赖固定睡眠竞态。
- 必须检查BackupScreen的返回、取消、选择文件回调、错误与成功路径实际采用新规则，不仅测试未接入的辅助函数。
- 保留Room聚合事务/ABORT、原库不变、新ID、旧记录/草稿/随机子集、PDF资源边界及已有解析/判题/统计/匹配规则。继续禁止打开本机真实用户库试恢复。
- 全部正式JVM、原7项独立测试、共享解析回归、assembleDebug/assembleDebugAndroidTest实际运行；22项或新增仪器测试无设备只编译，未执行如实写明。
- 新APK outputs/apk/offlinequiz-03d2-r1-debug.apk，versionCode>14、原applicationId/签名，保留所有旧包，无INTERNET/广泛存储/真实样本，不提交推送发布、不改项目外设置凭据。
- 报告 docs/reports/03d2-r1-dsh-report.md，更新设备清单/设计（如必要）、STATUS为PHASE_03D2_R1_READY_FOR_REVIEW或准确阻碍及交接，区分实际验证/未验证。
- 完成即停止，用户手动叫Codex验收，不继续下一阶段。
