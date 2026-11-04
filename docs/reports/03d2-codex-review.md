# 阶段 03D2 独立验收

日期：2026-11-03。结论：**CHANGES_REQUESTED**。手机入口、备份服务及Room事务接线已实现，取消/迟到回调仍需集中返工。用户既有授权验收后直接下达，任务 docs/tasks/03d2-revision-01.md。

## [P2] 导出到目标文件的复制不响应取消，且提前打开目标

BackupService.export在临时ZIP校验后使用input.copyTo(output)，循环没有协程取消检查。BackupViewModel.tryLeave取消exportJob并允许离页，但同步复制仍继续写目标。

新增独立测试直接驱动实际BackupService，使用自制PDF、内存Store和记录输出流；目标第一块写入时取消该导出Job，之后仍发生**5次**目标写入：

```text
REVIEW export target writes after cancellation=5
expected:<0> but was:<5>
```

测试实际编译运行后业务断言失败，不是工具/环境失败。源码位于work/codex-review-03d2/tests/BackupExportCancelReviewTest.kt。

另，BackupViewModel.export先openOutputStream(uri)，再执行临时生成与校验；真实DocumentProvider可能立即创建/截断目标。应在本地生成并校验成功后才打开目标流。系统选择器可能先创建空占位文件及最终复制失败可能部分写入是平台边界，但不应在生成失败前提前打开写流。

## [P2] 备份页面没有请求隔离，旧取消/失败回调可覆盖新操作

BackupViewModel使用共享cancelFlag；取消时立即state=Idle，Job.cancel后isActive=false即可再次开始，新请求又把共享flag置false。所有旧协程catch/完成回调无generation/requestId检查，仍能无条件写Idle/Error/Preview。迟到的旧请求会改掉新请求的Preparing等状态；取消标记也会被新请求复用。cancelFlag从IO线程读取且没有独立线程安全请求状态。

这项依据实际源码路径复核，未声称Compose真机或实际ViewModel可控延迟测试已执行。现有新增JVM测试主要是服务层，无BackupViewModel状态回归。需提供可注入边界或实际接入的可测试状态控制器，补取消A→开始B→A迟到的测试，旧结果/finally不能干扰B。

取消异常也须保持真实语义：导出通用catch会把CancellationException当普通失败；confirm通用catch固定提示“未写入任何数据”，未检查PreparedRestore.committed。提交若已完成而调用协程随后被取消，不得误报未写入或清理已发布PDF；该边界需补可控取消测试，不能用顺序调用service.cancel(after commit)代替真实Job取消。

## 已核查与实际验证

- 确认F:\New project，阅读需求/交接/任务/报告，检查BackupService/ViewModel/Screen、RoomBackupDataStore/DAO及PDF/应用接线；未改产品实现。
- 生产Room读取四表处在同一事务、插入整聚合ABORT，未使用开始练习接口supersede旧会话；资源在DB之前校验/发布、提交标志在NonCancellable区域置位；手机SAF/预览/确认入口存在。
- 离线 :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest 成功：33类 **227 tests / 0 failures / 0 errors**；79任务中1执行/78 UP-TO-DATE。正式测试实际重跑，构建为增量检查。
- 原6项独立测试及新增导出取消测试实际运行 **7 tests / 1 failure**，原6项通过；额外测试通过work下init临时加入，不改产品Gradle或既有断言。
- 共享解析实际 **30 PASS / 0 FAIL**，含201题样本。
- work/codex-review-03d2/ 保存build.log、unit-summary.txt、review-tests.log、parser-tests.log及独立XML；默认测试结果最后为额外测试，227项汇总来自先前完整正式执行。
- 交付APK与当前构建产物SHA一致：6BBF7EBFADA112F64BFAC8BBDDC08500F8568DA3B6BB7C97A027E4C424364210；versionCode14/0.3d2-mobile-backup；apksigner通过/原证书，aapt2仅列内部权限，无INTERNET/广泛存储权限。

## 限制与后续

22项仪器测试仅编译，真实Room/系统DocumentProvider/触控/旋转/强杀/低存储未实测，未打开真实用户库。JVM内存Store不能替代真实Room事务结果。本轮不通过发布验收，直接下达03D2 R1，不提交推送发布。
