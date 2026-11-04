# 跨行/顿号规则独立验收

日期：2026-11-03。结论：**PHASE_02A_BLANK_RULES_CHANGES_REQUESTED**。

## 已核实

- 共享解析正式回归独立运行：23 PASS / 0 FAIL，退出码 0。
- 使用项目内环境、离线依赖和 --rerun-tasks 强制执行 Android 单元测试与 assembleDebug：BUILD SUCCESSFUL，46 项任务实际执行。Android JVM 测试 XML 为 4 tests / 0 failures / 0 errors。
- 交付 APK SHA-256 为 15EFCD40CA5217551BC998D2FF9EEE149EF990680219A66872F3523DC6903519，签名验证通过、证书与旧版一致，未声明联网或存储权限。
- 代码确实先合并跨行，再只按中文顿号拆学习空；对简答不处理。跨行事实不再直接变为红色校对，UI 原因映射为中文。
- 现有测试覆盖普通顿号、未划线间隙、边界歧义等，但遗漏实际提取时自动插入的排版空格。

## 必修 / P2：清洗答案偏移被当成原文偏移，泄露答案文字

定位：QuestionParser.java 的 buildLearningBlanks（约 445-446 行）。TextExtractor 和 AndroidPdfExtractor 在重建 line.text 时会根据字形间距插入空格，而 underline.cleanText 直接拼接字形，会没有这些空格。当前检测到 blank_text_mismatch 后仍使用 `group.sentenceStart + range[]` 将清洗答案的偏移直接当作原句偏移，因此替换位置错误。

已新增独立端到端复现：`work/02a-blank-rules/ReviewSpacing.java`，用真实 PDFBox 内容流生成带完整下划线的自制 PDF，答案为 `甲、 乙、丙`。通过实际 TextExtractor + QuestionParser，而非手工指定最终题目结果。

实跑结果：

```text
original=甲、 乙、丙
stem=____、____乙____丙
answers=[甲, 乙, 丙]
review=[blank_text_mismatch]
answer_characters_leaked=true expected=false
exit=1
```

期望三空且顿号保留，题干没有甲/乙/丙残留。现有 warning 不能替代正确的挖空结果，且单纯删除空格可能改变原文索引，不能作为修复。

复现资料：ReviewSpacing.java、review-spacing.pdf、codex-spacing-result-utf8.txt、run-review-spacing.py，均在 work/02a-blank-rules/。

## 下一步

仅执行 docs/tasks/02a-blank-rules-revision-01.md，完成即停，由用户通知复查。暂不推荐用户安装这一版，不进入 02B。原正常样本 201 题通过不代表新规则已泛化通过。
