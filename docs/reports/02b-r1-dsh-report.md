# 阶段 02B 返工 01 报告：可靠合并空与保存期间编辑

日期：2026-11-03。执行者：DSH。状态：`PHASE_02B_R1_READY_FOR_REVIEW`。真机未测。

## 结论

已按 `docs/tasks/02b-revision-01.md` 完成两项返工：合并空改为保守策略并清空合并空的旧可接受答案；保存期间全部编辑控件禁用，ViewModel/编辑状态统一拒绝修改，回读不再覆盖新输入。未修改已验收的 PDF 提取、下划线、仅顿号拆空和原文索引映射规则，未进入练习/统计阶段。

## R1：合并空

### 旧问题

- 旧实现只删掉当前空位，题干残留 `、`，`前____、____后` 变成 `前____、后`，复原后多一个顿号。
- 旧实现把两个空的可接受答案取并集，`[A]`、`[B]` 被当成合并后整空的正确答案，形成“半答案”。
- 旧测试断言的正是错误行为。

### 新规则

合并当前空到前一个空时：

1. **先检查结构**：题干占位符数必须等于答案组数；不等则拒绝并提示先校对，题干、答案、可接受答案全部不变。
2. **只允许安全的中间内容**：
   - 纯空白：答案用单个空格连接（如 `甲 乙`），不凭空补顿号。
   - 标点白名单：`、，,；;：:。.！!？?`。用该标点连接答案，并把两个占位符和中间标点整体替换成一个 `____`。
     - `前____、____后` + `[甲,乙]` → `前____后` + `[甲、乙]`。
   - 正文、连词或其他内容（如 `____和____`、`____后又____`）：拒绝自动合并，中文提示用户手动编辑；所有内容不变。
3. **答案连接**：仅用原中间内容，不无条件用顿号；两空答案必须非空，否则拒绝。
4. **可接受答案**：合并空的额外可接受答案清空，并提示“请重新设置”；其他空的可接受答案保持原顺序。不做两个旧集合的并集。
5. **序列号/顺序**：只合并指定相邻空，答案和多空顺序保持。

`BlankEditor.mergeBlank` 现在返回带 `notice`/`rejectedReason` 的 `MergeResult`；拒绝时原样回传输入内容。`QuestionEditViewModel.mergeBlank` 对拒绝显示中文提示且不修改题目。

## R2：保存期间编辑

### 新状态边界

新增纯 Kotlin `QuestionEditSession`（`model/QuestionEditSession.kt`）：

- `beginSave()` 在任何挂起调用之前设置 `saving = true`，并返回本次要保存的快照。
- `update(...)` 是所有修改的统一入口；`saving = true` 时直接返回 false，不修改题目。
- `finishSave()` 完成保存/回读后才解除 `saving`；保存成功才回读并更新 question/baseline。
- 保存失败/结构校验失败保留当前编辑内容、错误或提示；不会用旧快照覆盖。
- ViewModel 持有 session 并镜像 Compose 状态；`load()` 在保存进行中直接返回，避免旋转/重入重置正在保存的题。

### UI

`QuestionEditScreen` 在保存期间禁用：

- 题干、简答参考答案；
- 选项标记/内容、添加/删除选项；
- 单选/多选正确项、判断答案；
- 填空答案、可接受答案的增删改；
- 添加空、删除空、并入上一空；
- 底部“仅保存/保存并确认”和顶部返回。

只读操作（查看原页、展开原始识别记录）保留。保存失败后控件恢复，原编辑内容仍在。

## 测试补强

替换了旧的错误 merge 测试，新增真正检查正确行为的回归：

- 两空 `前____、____后`：题干无残留顿号、答案 `甲、乙`、可接受答案清空。
- 三空：合并前两空和合并后两空，其他空顺序及可接受答案保持。
- 全部标点白名单逐项循环：题干只留一个占位符，答案用对应标点连接。
- 纯空白：答案 `甲 乙`，不出现顿号。
- 正文间隔 `____和____`、`____后又____`：拒绝且状态完全不变。
- 占位符数/答案组数错位：拒绝且状态不变。
- 空答案：拒绝且不丢另一个答案。
- 保存中修改：用 `CompletableDeferred` 挂起保存，调用 update 必须返回 false；完成后回读仍是保存快照，不覆盖新输入。
- 保存失败：编辑内容保留且 dirty；结构错误：保留并显示 errors；成功：回读后清 dirty。
- 重复保存请求在第一次未结束时被拒绝。

未修改已验收的共享解析测试。

## 实际验证

### 共享解析

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1
```

结果：`PASS=30 FAIL=0`，退出码 0，含真实样本 `201 questions, 58 underlines` 集成回归。

### Android JVM 单测

强制重跑 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`：**BUILD SUCCESSFUL，79 个任务全部执行**。

JUnit XML 汇总：**42 tests / 0 failures / 0 errors / 0 skipped**。

- `BlankEditorTest`：10
- `LibraryRepositoryTest`：8
- `QuestionEditSessionTest`：5
- `QuestionMapperTest`：2
- `QuestionParserTest`：4
- `QuestionValidatorTest`：8
- `QuizJsonTest`：5

### Codex 复现

用 `work/codex-review-02b/ReviewBlankMerge.java` 直接调用本次实际 Kotlin 编译产物（`android/app/build/tmp/kotlin-classes/debug`）：

- 退出码 `0`
- `dangling_separator=false`
- `partial_answer_accepted=false`
- `____和____` 不再被自动合并（JUnit 明确断言拒绝且内容不变）
- 干净摘要：`work/02b-r1-merge-check/repro-summary.txt`

### APK

- 路径：`outputs/apk/offlinequiz-02b-r1-debug.apk`
- 大小：42,937,121 字节
- SHA-256：`BB3BBF43EAF5EA49575F3700A761DDC0854FF3A7521FFE70841DA8FD0733C03D`
- applicationId：`com.offlinequiz.app`
- versionCode：5；versionName：`0.2b-r1-library-edit`
- minSdk 29 / targetSdk 36 / compileSdk 36
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`，与 02B 及 02A 一致。
- 权限：无 `INTERNET`/存储/相机权限；仅 AndroidX 自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
- APK 243 个条目、0 个 `.pdf` 条目；未发现 `local-inputs`、`sample.pdf`、`New project`、`F:\New project` 等真实样本/本机路径。
- 旧 APK 全部保留，包括 `offlinequiz-02b-debug.apk` 和 02A 系列。

## 改动文件

- `android/app/src/main/java/com/offlinequiz/app/model/BlankEditor.kt`：保守合并、白名单、清空可接受答案、拒绝结果。
- `android/app/src/main/java/com/offlinequiz/app/model/QuestionEditSession.kt`（新增）：保存中统一防护、保存/回读状态边界。
- `android/app/src/main/java/com/offlinequiz/app/model/QuizModels.kt`：`SaveOutcome` 移入 model，供 session/repository 共用。
- `android/app/src/main/java/com/offlinequiz/app/data/LibraryRepository.kt`：引用 model 的 `SaveOutcome`，保存逻辑不变。
- `android/app/src/main/java/com/offlinequiz/app/viewmodel/QuestionEditViewModel.kt`：改用 session；merge 拒绝提示；保存中禁止修改/重载。
- `android/app/src/main/java/com/offlinequiz/app/ui/QuestionEditScreen.kt`：保存期间禁用全部编辑控件。
- `android/app/src/test/java/com/offlinequiz/app/BlankEditorTest.kt`：替换错误预期，新增 10 项合并回归。
- `android/app/src/test/java/com/offlinequiz/app/QuestionEditSessionTest.kt`（新增）：5 项挂起保存/失败/回读回归。
- `android/app/src/test/java/com/offlinequiz/app/LibraryRepositoryTest.kt`：更新 `SaveOutcome` import。
- `android/app/build.gradle.kts`：versionCode 5 / versionName `0.2b-r1-library-edit`。
- `tools/android-build.ps1`：产物名改为 `offlinequiz-02b-r1-debug.apk`。
- `android/README.md`、`docs/reports/02b-device-checklist.md`：更新 R1 说明和清单。
- 未修改 `tools/pdf-probe/src/pdfprobe/Model.java`、`QuestionParser.java` 或 Android 解析器。

## 尚未验证

- 本机无安卓设备/模拟器：7 个仪器测试（Room 隔离/事务回滚/重开持久化、私有 PDF 复制、非 PDF 拒绝、PdfRenderer 单页/越界）仅编译，未运行。
- 真机 UI 未执行：保存期间控件确实置灰、真实慢写入、键盘/旋转/进程死亡、Toast 提示、合并按钮流程均未在设备上验证。
- 02B R1 APK 未在 RMX3800 / Android 16 安装；真实 201 题导入、保存、重启恢复、来源页渲染仍待真机。
- 保存中编辑的回归是 JVM 状态边界测试，不是真机 UI 自动化。
- 未验证低存储、权限撤销、损坏/超大 PDF、其他版式、深色/无障碍。
- 未做 release 构建或正式签名。

## 阻碍

无实现阻碍。唯一限制是没有安卓设备/模拟器；按要求没有安装模拟器镜像、Android Studio 或修改系统设置。

## 最短手机检查步骤

完整清单见 `docs/reports/02b-device-checklist.md`。最短：

1. 安装 `offlinequiz-02b-r1-debug.apk`，确认 versionCode 5、无网络/存储权限。
2. 导入真实样本 → 保存 → 首页显示题库；强制停止重开仍在。
3. 打开 `前____、____后` 填空题，给两个空设 `[A]`、`[B]`，合并：期望 `前____后`、`甲、乙`、可接受答案清空并提示，不能残留 A/B。
4. 打开 `____和____`，合并：期望拒绝且内容不变。
5. 保存一题时快速尝试输入/点击：期望全部编辑控件禁用；保存完成后内容为保存开始时内容，无回读覆盖。
6. 制造填空空数/答案数不一致，合并：期望拒绝并提示先校对。
7. 查看原页；有设备时运行 `connectedDebugAndroidTest`（7 项）。

## 停止

仅完成 02B R1 返工。未提交、推送、发布，未进入练习/统计。完成并写报告后停止，等待用户手动通知 Codex 验收。
