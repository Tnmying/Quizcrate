# 阶段 03B R2 独立验收

日期：2026-11-03。结论：**CHANGES_REQUESTED（剩余一项UI收尾）**。上次取消确认的缺陷已修复，两份原独立测试均通过；提交期间仍有离页入口未保护。用户已授权验收后直接继续，下一任务为 docs/tasks/03b-revision-03.md，不进入03C。

## 已通过复核

- confirmStartPractice 捕获 expectedActiveId，使用令牌及任务句柄，复查挂起后校验请求身份；取消作废请求，CancellationException重新抛出；旧finally仅清理自己的状态。
- 提交前可取消；提交阶段committing=true拒绝取消，正常成功仍自动进入新练习。正式新增7项测试涵盖迟到返回、重试、防重复及提交边界。
- 原 WrongbookConflictReviewTest、WrongbookCancelReviewTest 未改，Codex实际原样复跑通过；原取消用例输出 new=false started=null。

## [P2] 提交中仍能经题目行离开错题本

- WrongbookViewModel.kt:234-237 在 committing=true 后立即清空 pendingStartMode，弹窗关闭，再挂起调用仓库。
- WrongbookScreen.kt:268-269 把历史/校对回调直接传给题目行；407-408 两个TextButton无enabled限制。MainActivity.kt:88-89 收到回调即切换路由，无提交保护。
- 因此提交慢时可点“查看历史”或“校对题目”进入其他页，后台继续新建。错题本不在组合中时成功导航Effect不运行，返回错题本后仍可能消费迟到的startedPracticeId，产生意外跳转。这与本轮采用的“提交期间不能离页”规则不一致。
- 顶部返回/BackHandler/继续按钮已保护，但行内入口遗漏；错误态“返回题库”及条件性“去校对题目”也需统一核查，避免分散保护再次漏项。
- 以上为实际源码路径核查，未声称已在手机触控复现。建议统一离页守卫或提交阶段模态等待；补测试并核查UI全部接入，保留成功后的自动导航。

## Codex实际验证

- 工作目录 F:\New project。读需求、交接、任务及DSH报告，独立检查实现/正式测试，未改产品代码。
- 离线强制执行 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks：BUILD SUCCESSFUL，79任务全部执行；正式23类149 tests / 0 failures / 0 errors，汇总 work/codex-review-03b-r2/unit-summary.txt。
- 两份原独立测试通过work下init临时加入测试源，实际执行2 tests / 0 failures，日志 review-tests.log，结果XML另存同目录。第一次相对init路径被Gradle按android目录解析而失败，改为绝对路径后成功；不是产品失败。
- 共享解析独立重跑30 PASS / 0 FAIL，含201题样本，日志parser-tests.log。构建日志build.log均在work/codex-review-03b-r2/。默认Gradle结果目录被额外测试覆盖，149项汇总来自此前正式完整运行。
- 交付APK与独立重建SHA-256一致：B887C37E315688D8DD7AACC1D6BC23495CC83817986B901CE0BC7D26ADBA8E88，43,592,481字节；versionCode10、0.3b-r2-wrongbook；apksigner验证通过、原调试证书沿用；aapt2仅列应用内部签名权限，无INTERNET/存储权限。

## 限制与后续

19项仪器测试仅编译未执行；真实Room、触控/导航、旋转/进程恢复仍待设备。未提交推送发布。按用户授权下达03B R3集中收尾任务，完成后停止等用户通知验收。
