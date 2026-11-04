# 阶段 02B R1 独立验收

日期：2026-11-03。结论：**PASS（代码/逻辑/构建验收；真机待验证）**。授权进入 03A，具体范围见 `docs/tasks/03a-practice.md`。

## 实际验证

- 确认工作目录，读取需求/返工任务及报告，独立检查 BlankEditor、QuestionEditSession、ViewModel 和全部编辑控件的 saving 防护。
- 强制离线重建 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`：BUILD SUCCESSFUL，79 个任务全部执行；JUnit XML 汇总 **42 tests / 0 failures / 0 errors**。
- 独立重跑共享 PDF 回归：**30 PASS / 0 FAIL**，含真实样本 201 题。
- 重新编译并运行上轮 `ReviewBlankMerge.java` 调用实际 Kotlin 编译产物：退出码 0。题干 `前____后`、答案 `[甲、乙]`、额外可接受答案为空，正文间隔保持原样。
- 新增独立 `work/codex-review-02b-r1/ReviewMergeInvariants.java` 并实际执行：三空中间合并保持顺序/原分隔符、无半答案、正文间隔全量保持不变、空数错位拒绝，共 4 项 PASS。
- 保存防护测试实际执行，包含 CompletableDeferred 挂起保存期间拒绝修改、成功回读、失败保留编辑及结构错误反馈；ViewModel 所有修改经过 session，UI 编辑字段/按钮在 saving 时均禁用。
- 交付 APK 与独立重建 APK SHA-256 一致：`BB3BBF43EAF5EA49575F3700A761DDC0854FF3A7521FFE70841DA8FD0733C03D`；versionCode 5；applicationId/调试签名沿用；aapt2 检查无 INTERNET/存储权限。
- 日志与独立复现均在 `work/codex-review-02b-r1/`，未改产品实现或伪造测试结果。

## 边界

- 本轮解决上一轮合并空、可接受答案及保存期间输入覆盖问题，没有发现阻止进入下一开发阶段的新问题。
- 无安卓设备：7 个仪器测试仅编译未运行，不能声称 Room/URI/PdfRenderer 真机通过；新版安装、导入保存重启、编辑禁用/提示及来源页仍需手机检查。
- 交付 APK：`outputs/apk/offlinequiz-02b-r1-debug.apk`。尚为 debug 构建，未发布。
