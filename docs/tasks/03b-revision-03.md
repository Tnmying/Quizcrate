# 阶段 03B 返工 03：补齐提交期间的离页保护

2026-11-03 用户授权：验收后如无大问题，直接让DSH继续。R2的取消请求作废逻辑已复核，本轮仅补齐提交阶段的UI入口遗漏，暂不进入03C/n-gram、备份或发布。

先确认 F:\New project，读 AGENTS、PROJECT_BRIEF、DSH_HANDOFF 与 docs/reports/03b-r2-codex-review.md。

## 要完成的修复

- WrongbookViewModel 在确认成功后将 committing=true、pendingStartMode=null，确认弹窗关闭；此时 WrongbookScreen 的 WrongbookRow 仍直接调用 onOpenHistory/onEditQuestion，行内 TextButton 未禁用，可以绕过顶部/系统返回的提交保护。错误态“返回题库”和条件性的“去校对题目”也应统一核查。
- 将错题本所有用户触发的离页路径统一接入同一规则：提交中不离页并给出已有中文提示；提交结束/失败后恢复可用；确认复查期间若允许离页必须先作废确认请求。成功创建后的自动进入新练习属于合法完成路径，不得被拦截。
- 可采用覆盖提交阶段的模态等待，或统一导航保护并配合按钮状态。不要只禁用几个按钮而遗漏回调入口；不要删除查看历史或校对功能来规避。
- 保留R2令牌/作业取消/挂起后校验/旧finally隔离、原三项修复、统计与解析规则。集中改动，不新增框架/数据库升级。

## 验证与交付

- 覆盖提交中查看历史、校对、返回的导航请求均不执行，提交结束/失败后恢复；正常成功只导航一次。若提取可测试导航守卫，要确认UI全部相关回调实际接入它。无设备时如实注明源码接线检查和JVM测试，不能称Compose触控实测。
- 全部正式Android JVM测试、两份既有Codex独立测试原样复跑；assembleDebug/assembleDebugAndroidTest，解析回归。复用项目work内环境，不改项目外设置或凭据。仪器测试无设备只编译。
- 新APK outputs/apk/offlinequiz-03b-r3-debug.apk，versionCode>10、原applicationId和签名，保留旧APK。不提交推送或发布。
- 写 docs/reports/03b-r3-dsh-report.md，列清导航入口、实际验证/日志、APK信息、未验证项；更新STATUS与交接，完成后停止等用户叫Codex验收。
