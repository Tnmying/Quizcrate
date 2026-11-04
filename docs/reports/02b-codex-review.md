# 阶段 02B 独立验收

日期：2026-11-03。结论：**CHANGES_REQUESTED**。本地保存/编辑实现已完成大部分工作，但合并空会产生错误答案数据，暂不进入练习阶段。

## 实际验证

- 确认工作目录为 `F:\New project`，读取需求基线、任务和实现报告，检查模型、数据库/仓库、编辑界面与 ViewModel、私有 PDF 保存及渲染代码。
- 独立离线强制重建：`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`，79 个任务全部执行，构建成功；JUnit XML 汇总 32 tests / 0 failures / 0 errors。
- 独立运行共享 PDF 回归：30 PASS / 0 FAIL，含真实样本 201 题。
- 交付 APK 与独立重建 APK SHA-256 一致：`9CE2FA3CA0BA45569244F154DB2DFD2EE8F40E65EF3150F2A0E2A71F481B0575`。aapt2 permissions 检查无 INTERNET/存储权限。
- 构建及回归日志位于 `work/codex-review-02b/`。

## 必须修复

1. **合并空错误继承局部可接受答案。** `BlankEditor.mergeBlank` 对两个空的可接受答案取并集。例如参考答案 `[甲, 乙]`、可接受答案 `[[A], [B]]` 合并后得到参考答案 `甲、乙`，可接受答案却是 `[A, B]`。这些只回答一半，不能作为整个合并空的正确答案。后续判题若使用这些数据就会错判。现有 `BlankEditorTest` 反而断言了错误行为。
2. **合并后题干与参考答案不对应。** `前____、____后` 合并得到 `前____、后`、答案 `甲、乙`，复原成 `前甲、乙、后`，多出顿号；`____和____` 得到 `____和`、答案 `甲、乙`，还改变了原句关系。独立 Java 复现调用实际 Kotlin 编译产物，两个断言失败，退出码 1。脚本 `work/codex-review-02b/ReviewBlankMerge.java`，日志 `blank-merge.log`。
3. **保存期间可继续修改但回读会覆盖新输入。** 静态确认：`save` 捕获快照并挂起等待仓库；表单未按 saving 禁用，`mutate` 无 saving 防护；保存成功回读时无条件覆盖 question/baseline。慢写入期间新输入可能无提示丢失。此项为可达代码路径检查，尚未在设备上复现，应通过可控挂起保存的回归验证并修复。

## 尚未验证

- 7 个安卓仪器测试只编译，未运行；本轮无设备，不把编译当作 Room、content URI 或 PdfRenderer 实测。
- 新版 APK 在用户安卓手机的安装、导入、编辑、保存重启及来源页显示仍待真机验证。

下一任务：`docs/tasks/02b-revision-01.md`。修复后重新交付，保留先前 APK；本轮不进入 03。
