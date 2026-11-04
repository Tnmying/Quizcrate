# 跨行/顿号规则返工 01：排版空格与原文映射

先读 AGENTS.md、PROJECT_BRIEF.md、原规则任务和 docs/reports/02a-blank-rules-codex-review.md。仅修复这一问题，不扩大到完整刷题或 02B。

1. 复现 work/02a-blank-rules/ReviewSpacing.java；保留 Codex 的复现和日志，不覆盖审查资料。
2. 修复学习子空偏移。应依据原文范围/字形映射定位子空，不能把去空格的答案索引直接加到原文起点。可以在原文精确片段上分段，再单独规范答案，或建立可靠映射；不要直接删掉原文所有空格掩盖问题。
3. 原文/cleanText 不一致时，优先正确映射；无法可靠映射则保守退回完整原始范围作为一个空并标明原因。不能继续输出错位子空、吞掉顿号或露出部分答案。
4. 同一共享规则同时服务 Android 与 JVM。先合并跨行、仅中文顿号拆空、未划线正文不吞、简答不转换等已确认规则保持。
5. 新增正式的真实 PDF 提取到解析端到端回归，至少包含顿号前后空格、首尾/重复排版空白、跨行带空格，以及普通无空格对照；断言空数、完整题干、标点、答案顺序与不泄露答案。不要只用手工 geometry 证明解析正确。
6. 重跑现有共享回归、Android 单元测试与构建，复核真实样本 201 题/58 线的语义变化。解释任何计数或警告变化。
7. 新 APK 输出 outputs/apk/offlinequiz-02a-blank-rules-r1-debug.apk，提高 versionCode、沿用应用标识/签名，保留旧 APK。
8. 写 docs/reports/02a-blank-rules-revision-01-dsh-report.md，更新 STATUS 为 PHASE_02A_BLANK_RULES_R1_READY_FOR_REVIEW 或真实阻碍；区分桌面/构建与真机验证。

不安装新 SDK/插件，不修改系统设置，不推送/发布。完成后停止，用户会手动叫 Codex 验收。
