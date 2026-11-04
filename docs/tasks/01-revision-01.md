# 阶段 01 返工 01

先读 `AGENTS.md`、`docs/PROJECT_BRIEF.md` 和 `docs/reports/01-codex-review.md`。当前仅授权本次返工，不进入阶段 02，不安装安卓 SDK 或修改外部环境。

1. 修复验收报告 R1：PDF 路径构造与绘制区分。不要只对独立回归的特殊数据打补丁。
2. 修复 R2：真实选项标记保留/验证，重复、逆序、缺号和超出支持范围必须可见，不静默吞并。允许明确限制支持格式并标记待校对；不要声称支持所有 PDF。
3. 补充项目内可重复执行、遇到错误返回非零的正式回归。Codex 临时 ReviewRegression.java 是复现线索，请自建正式测试而非只修改审查器的预期。
4. 重新跑真实样本，保留旧交付，在 `work/phase-01-revision-01/` 输出结果；对比数量、答案、下划线和待校对状态，解释变化。
5. 修正 R3 的 API 版本、许可证描述；重新评估而不必换库。核实所选库的维护/版本证据。报告中将“Android 尚未构建/运行”与“解析缺陷”分开。
6. 输出 `docs/reports/01-dsh-revision-01.md`，列出实际测试命令、通过/失败数、样本对比和未验证项目。更新 `docs/STATUS.md` 为 `PHASE_01_REVISION_READY_FOR_REVIEW` 或 `PHASE_01_REVISION_BLOCKED`。

完成后停止，等用户通知 Codex 检查，不自行进入下一阶段。无需安装任何协作插件。
