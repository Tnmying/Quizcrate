# 阶段 03D1 返工 01：练习位置语义与完整进度校验

2026-11-03 用户既有授权：验收后直接下指令。先确认F:\New project，读AGENTS、PROJECT_BRIEF、DSH_HANDOFF与docs/reports/03d1-codex-review.md。仅完成两项集中返工，不进入03D2/生产恢复/发布。

## 1. 不混淆题库位置与本轮索引

- BackupValidator要求referenceSnapshot.position == answer.position错误：snapshot.position为题库位置，answer.position为session.questions数组索引。
- 按真实PracticeRepository语义核对answer与session.questions[answer.position]的题目身份、libraryId与历史参考快照；原题库位置保留不重排，随机顺序/错题子集也不改写成题库顺序。不用删除历史快照或绕过全部校验来规避。
- 正式覆盖：题库第6题作为错题重练第1题作答；随机重排后作答；顺序会话；导出→读取→新ID恢复计划后所有引用/位置/旧题快照均正确。至少一项通过真实PracticeRepository生成已作答的错题重练，避免合成数据掩盖语义差异。

## 2. 核对阶段所需完整作答集合

- 当前只验证已经存在的答案，active/feedback/0且无答案仍能导出并读入。按既有合法进度语义：answering时已作答位置应为0到currentIndex-1，feedback时应为0到currentIndex，completed时应覆盖全部题；superseded保留其当时phase与相同位置规则。
- 缺失、中间断档、超前记录或phase不匹配明确拒绝，不补假作答、不丢现有记录或静默重置进度；空会话/不合法索引等既有保护保留。原有正式样例若其实不符合生产语义，应修正样例并说明，不能放宽核心规则来迎合样例。
- 正式正反例覆盖三阶段、superseded和随机/子集、跳过与自评、缺失旧历史快照（允许缺失referenceSnapshot，但不能缺失作答本身）。反馈快照有内容时与当时会话题目保持一致，不用当前已编辑题库内容补旧记录。

## 验证与交付

- work/codex-review-03d1/tests/BackupSessionReviewTest.kt 原样复跑，init同目录含既有4项独立测试（共6项）。不要修改独立断言/任务/验收记录。
- 全部正式JVM、独立测试、共享解析回归、assembleDebug/assembleDebugAndroidTest实际运行；无设备仅声明仪器测试编译。保留原备份格式/资源限制/安全路径/新ID计划及已验收功能。
- 如需修改格式/校验策略，更新docs/design/backup-format.md说明；本轮不接手机入口、不试写真实库、不升APK版本或复制新包，保留03C R1交付包及旧APK。
- 报告 docs/reports/03d1-r1-dsh-report.md：两项修复、生产数据语义、实际测试/日志、未验证/阻碍；更新STATUS为PHASE_03D1_R1_READY_FOR_REVIEW或准确阻碍及交接。
- 完成后停止等用户叫Codex验收，不继续03D2、不提交推送发布，不改项目外设置或凭据。
