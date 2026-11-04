# 阶段 03B 返工 02：取消确认必须作废待完成请求

2026-11-03 用户授权继续推进。先确认工作目录 F:\New project，读 AGENTS.md、PROJECT_BRIEF、DSH_HANDOFF 和 docs/reports/03b-r1-codex-review.md。仅处理本轮取消边界；保留前三项已复核修复，不进入 n-gram、备份或发布。

## 问题与要求

- WrongbookViewModel.confirmStartPractice 复查活动会话挂起时，cancelPendingStart 只清空 pending 字段，无法作废已捕获 mode 的协程。当查询返回 null，会继续新建会话并导航。独立失败复现见 work/codex-review-03b-r1/tests/WrongbookCancelReviewTest.kt。
- 在实际写入前，取消必须使本次请求失效：延迟查询无论返回 null、原活动会话还是不同会话，都不得新建、替换旧会话或发出 startedPracticeId。结合请求令牌/任务句柄与挂起后校验实现，不要仅依赖可空会话 ID 比较或按钮 enabled。协程取消不能被通用异常捕获当普通失败继续处理。
- 弹窗取消、点外部/系统返回关闭、继续现有练习，都应采用一致规则。继续现有练习只能打开原有练习，不能迟到跳入新练习。若写入已进入不可取消提交阶段，明确提交边界，统一拦截所有取消/继续/关闭入口并显示状态，不能接受取消后仍后台新建。
- 请求取消后立即重新发起、切换题库或页面、重复确认等情况下，旧请求的返回/finally 不得清空或篡改新请求的状态，也不能重复写入。保持成功确认新建、会话变化再次确认、失败重试正常。
- 改动集中，复用现有结构/schema3，保留统计、历史入口/快照、判题、幂等、进度恢复、PDF/拆空规则，不新增框架。

## 验证与交付

- 正式测试覆盖挂起复查时取消，返回 null/原会话/不同会话；取消后重试与迟到返回；重复确认；失败/取消后恢复；正常明确确认仍可创建。核查 UI 所有退出路径实际接入保护，不能只测试一个未使用的辅助函数。
- 原样执行两份独立测试：work/codex-review-03b/tests/WrongbookConflictReviewTest.kt 与 work/codex-review-03b-r1/tests/WrongbookCancelReviewTest.kt。可使用 work/codex-review-03b-r1/reviewer.init.gradle 临时加入测试源；不要修改验收测试/断言、任务说明或验收记录来规避失败。
- 执行全部正式 Android JVM 测试、共享解析回归、assembleDebug、assembleDebugAndroidTest。明确正式与额外独立测试的数量/日志，避免测试结果被后一次覆盖造成误报。无设备时仪器测试仅编译，不宣称真机验证。
- 新 APK outputs/apk/offlinequiz-03b-r2-debug.apk，versionCode > 9，沿用 applicationId/签名，保留旧 APK。检查 SHA、权限和样本私有边界。不提交、推送或发布。
- 报告 docs/reports/03b-r2-dsh-report.md：取消/提交边界、实际修复、测试实际结果和日志位置、APK信息、尚未验证/阻碍、最短真机步骤。更新 STATUS 为 PHASE_03B_R2_READY_FOR_REVIEW 或准确阻碍，更新交接。
- 完成即停止，等待用户手动叫 Codex 验收，不自动进入下一阶段。
