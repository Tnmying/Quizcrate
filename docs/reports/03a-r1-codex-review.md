# 阶段 03A R1 独立验收

日期：2026-11-03。结论：**PASS（代码/逻辑/构建验收，真机待测）**。上轮练习页首次加载阻断已修复，本轮没有发现阻止通过返工验收的问题。验收后停止，未下达后续任务。

## 实际验证

- 确认根目录，读取需求、交接、返工任务及报告，独立检查实际 ViewModel 加载路径、生产构造入口、界面重试按钮及新增 11 项入口回归。
- `loading` 初始 false，读取任务按 loadJob/会话 ID 防重入；首次调用能启动读取，成功/缺失/异常均结束加载。读取迟到保护、保存/退出保护保留。同会话重入保留未落盘输入。
- 独立离线强制重建 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`：**BUILD SUCCESSFUL，79 个任务全部执行**。
- 强制构建后的正式 JUnit XML 汇总：**83 tests / 0 failures / 0 errors**，含新增 11 项真实 ViewModel 测试（首次成功/缺失、失败重试、防重复读取、草稿/反馈/完成会话恢复、未落盘输入及重复提交保护）。
- 独立运行共享 PDF 回归：**30 PASS / 0 FAIL**，含真实样本 201 题。
- 原样复跑上一轮失败的 Codex 独立入口测试，未修改断言或脚本：**1 test / 0 failures / 0 errors**，实际输出 `sessionReads=1 loading=false`，缺失会话显示中文错误。此测试仍使用生产 Application 单参数构造入口。
- 日志在 `work/codex-review-03a-r1/`：`build.log`、`unit-summary.txt`、`parser-tests.log`、`entry-test.log`、`entry-test-result.xml`。默认 Gradle 测试结果目录最后显示额外入口测试，83 项全量结果在此前完整强制构建中已确认。

## APK

- `outputs/apk/offlinequiz-03a-r1-debug.apk`，43,281,185 字节，versionCode **7** / versionName `0.3a-r1-practice`。
- 独立重建与交付文件 SHA-256 一致：`F2047B99E5DD1B4B97591CBE20E9AE350DC1B390123201B11B1C5C613F72D0AE`。
- applicationId `com.offlinequiz.app`；apksigner 验证通过，调试证书 SHA-256 沿用 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`；aapt2 权限检查无 INTERNET/存储权限。

## 尚未验证

- 无安卓设备，13 个仪器测试仅编译未运行；Room 迁移、真实持久化与 Compose 完整流程不能宣称真机通过。
- 手机覆盖安装、首次顺序/随机/继续练习出题、旋转/强杀后的草稿与反馈恢复仍需按 `docs/reports/03a-device-checklist.md` 检查。
- 本次通过的是代码/逻辑/构建验收，尚非正式发布验收；未提交、推送、发布，也未进入下一开发阶段。
