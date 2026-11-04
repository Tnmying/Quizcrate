# 阶段 03A 返工 01：练习页首次加载

2026-11-03 用户已明确授权 DSH 返工，先前“验收后等待”的限制仅对此返工解除。先确认目录，读 AGENTS、PROJECT_BRIEF、DSH_HANDOFF、`docs/reports/03a-codex-review.md` 与本任务。仅修复 03A，完成后停止，等待用户手动叫 Codex 验收。

## 必须解决

`PracticeViewModel.loading` 初始 true，而 `load()` 在 loading=true 时直接 return。首次打开练习永远不访问仓库，界面一直转圈。不能只修改已有仓库测试或声称构建通过就已修好。

- 区分“首次展示加载状态”与“已有加载任务正在运行”，让首次调用确实启动读取，并可靠结束 loading。保留保存/退出期间的状态保护。
- 有效会话要显示题目/已保存草稿；缺失会话或读取失败要退出转圈并显示中文错误，用户可以返回或重试，不能永久卡住。
- 重复调用不能启动多份并行读取；旧请求回调不能覆盖新会话。旋转/重入不能覆盖尚未落盘的输入或重复记录答案。
- 恢复已提交反馈或已完成会话时，显示保存的阶段/结果，不重洗随机顺序、不重复提交、不回到未作答。
- 修复保持集中。无需更改 Room schema/迁移、解析/拆空规则或判题语义；不进入错题本、长期统计、n-gram、备份或发布。

## 必须验证

- 添加正式入口/状态层回归，实际覆盖首次成功加载、首次缺失/读取失败、失败后重试、正在读取时重复调用、草稿和反馈恢复；测试应能在旧缺陷上失败。只测试 Repository 不够。可用最小依赖注入改善可测性，不重搭工程。
- 原独立复现在 `work/codex-review-03a/tests/PracticeEntryReviewTest.kt`，通过 `work/codex-review-03a/reviewer.init.gradle` 临时加入测试源。实际用它复核首次入口；不要改验收脚本断言规避失败。如最小构造调整确需适配测试，明确记录适配及原因。
- 运行全部正式 Android JVM 测试、共享解析回归、assembleDebug 与 assembleDebugAndroidTest。仪器测试无设备仍只编译，明确未执行；不要新增安装模拟器/全局工具。
- 交付 `outputs/apk/offlinequiz-03a-r1-debug.apk`，提高 versionCode（当前 6），沿用 applicationId/签名，保留旧 APK。
- 写 `docs/reports/03a-r1-dsh-report.md`：根因、修复边界、实际执行结果、入口复现结果、APK SHA、未验证项目及最短真机步骤；更新 STATUS 为 `PHASE_03A_R1_READY_FOR_REVIEW` 或准确阻碍。
- 不覆盖 Codex 原验收记录，不提交推送发布，不修改项目目录外设置。完成后停止，不自行进入下一阶段。
