# 阶段 02B 本地题库保存与题目校对报告

> 后续：本报告的合并空与保存期间编辑问题已由 `docs/reports/02b-r1-dsh-report.md` 修复；`offlinequiz-02b-debug.apk` 仅作历史 APK 保留，最新交付为 `offlinequiz-02b-r1-debug.apk`。

日期：2026-11-03。执行者：DSH。状态：`PHASE_02B_READY_FOR_REVIEW`。真机未测。

## 结论

已完成 02B 范围：本地多题库保存、导入草稿、题目校对/编辑、原 PDF 来源页对照，以及应用重启后恢复已保存内容。没有进入顺序/随机练习、判分、错题本、统计、简答匹配或备份阶段。

共享解析回归和 Android JVM 测试实际运行通过，`assembleDebug` 与 `assembleDebugAndroidTest` 实际构建通过。当前环境没有安卓设备/模拟器，因此 Room 数据库、真实 `content://` 文件复制、`PdfRenderer` 和完整界面流程**没有在真机执行**；提供了 7 个仪器测试和手工清单，均未运行。

## 用户流程

1. 首页导入 PDF → 后台解析 → 预览题量、题型、来源页和待校对提示。
2. 为题库命名并保存；PDF 复制到应用私有目录，题库和题目在一个数据库事务内写入。
3. 首页显示多个题库：名称、题量、待校对、已确认、来源文件、更新时间；可重命名、可重复导入（生成新 ID，不覆盖旧库）。
4. 打开题库 → 按全部/待校对/已确认筛选 → 打开题目编辑。
5. 编辑后可“仅保存”或“保存并确认”；修改已确认题会使其回到待校对，需重新确认。
6. 题目内“查看原页”按来源页渲染原 PDF，仅渲染当前页，关闭后释放资源。
7. 强制停止或重启应用后，已保存题库、题目修改和确认状态从本地数据库恢复。

## 数据结构与持久化

### Room

- Room 2.8.5（runtime / ktx / compiler，KSP 2.2.21-2.0.5）。实现时通过 Google Maven 元数据核对 2.8.5 为当前稳定版，POM 依赖与当前 compileSdk 36 / Kotlin 2.2.21 兼容。
- 数据库：`offlinequiz.db`，schema version 1，WAL 日志。
- 导出的 schema：`android/app/schemas/com.offlinequiz.app.data.QuizDatabase/1.json`。
- 未启用 destructive migration。未来增加练习记录/统计时必须在 `QuizDatabase.VERSION` 提升版本并添加显式迁移，保留题库和题目 ID。
- 题目外键指向题库并级联删除；唯一索引 `(libraryId, position)` 保证同库题目顺序；题库 ID 和题目 ID 都是稳定 UUID，不同文档/相同题号不会互相覆盖。

### 表与字段

`libraries`：

- `id`、`name`、`createdAt`、`updatedAt`
- `sourceFileName`、`sourcePageCount`、`pdfRelativePath`
- `schemaVersion`

`questions`：

- 身份/来源：`id`、`libraryId`、`position`、`type`、`originalNumber`、`numberValue`、`sectionTitle`、`sourcePagesJson`、`sourceLinesJson`
- 可编辑内容：`stem`、`referenceAnswer`、`optionsJson`、`answersJson`、`acceptableAnswersJson`
- 填空证据：`blanksJson`（原始区域、跨行、顿号拆分、来源片段、学习空原文范围）、`learningBlankCount`
- 原始识别证据：`originalStem`、`originalOptionsJson`、`originalAnswersJson`、`originalBlanksJson`、`originalReviewReasonsJson`、`originalNeedsReview`
- 校对证据：`confirmed`、`confirmedAt`、`editedAt`

选项、答案组、来源行、填空区域和片段使用集中式 JSON 编解码（`QuizJson`），关系列保留可查询的题目身份。`stemWithAnswer`、`originalSentence`、`optionRawText` 也保留用于排查。

### 写入与失败语义

- 导入：先复制 PDF 私有副本；再在一个 Room 事务中写题库行和全部题目。任一步失败会删除新副本，不写题库，旧题库不受影响。
- 保存题目：先读取当前行并校验；单行更新并在同一事务更新题库 `updatedAt`。失败返回中文提示，旧内容不变。
- 重命名：单行更新；名称为空或超过 80 字符拒绝。
- 私有 PDF：`files/libraries/<libraryId>/source.pdf`。复制前校验 `%PDF-` 头、非空、同目录临时文件 + `fd.sync()` + rename；失败清理目录。启动时只清理超过 1 小时且没有题库行的孤儿目录，避免误删正在保存的副本。
- 原页预览：`PdfRenderer` 只打开当前页；`page/renderer/ParcelFileDescriptor` 在 finally 中关闭，位图在对话框关闭时回收。不缓存整份文档。

### 校对状态

- 导入时所有题 `confirmed=false`，原始识别提示完整保留。
- “仅保存”不会无条件清空已有确认；内容未变时保持原状态。
- 内容改变后保存会把题目标为未确认；本次同时点“保存并确认”才记录新确认时间和 `confirmedAt`。
- 待校对数量 = `confirmed = 0` 的题数；原始提示与当前确认状态分开显示，不会因为保存而删除原始诊断。
- 界面只显示中文标签；未知诊断显示“其他识别提示”，原始英文代码仅留在数据库。

## 校验规则

保存题目时拒绝：

- 空题干（含纯空白）。
- 选择题少于两个选项、选项标记为空/非单个 A-Z/重复、选项内容为空。
- 正确项不在现有选项中。
- 单选题正确项不是恰好一个。
- 多选题少于两个不同正确项或正确项重复。
- 填空题干没有空位、空位数量与答案组数不一致、任一空答案为空、可接受答案组数不一致（非空时）。
- 简答题参考答案为空。

填空编辑操作：

- “添加一个空”同步添加 `____` 和一组空答案。
- “删除”删除对应序号的 `____` 和答案，其他答案顺序不变。
- “并入上一空”删除当前 `____`，把两个答案用中文顿号合并，可接受答案按顺序合并去重。
- 手动改坏题干空位数量后保存会被校验拦截。

没有自动把简答转填空，没有生成 AI 答案，没有按顿号以外标点拆空。

## 界面

- 首页：空状态、题库卡片、题量、待校对、已确认、来源文件、更新时间和重命名。
- 导入：解析中/保存中进度；预览名称、题量、页数、下划线、题型分布、待校对数量、前 50 题；保存失败反馈。
- 题库详情：返回、重命名、题量/待校对/已确认、全部/待校对/已确认筛选、原 PDF 副本状态、题目列表。
- 编辑：题干；选择题选项/标记/正确项；判断 √/×；填空逐空答案和可接受答案、增删/合并空；简答参考答案；原始识别记录可展开；来源页预览。
- 返回时若有未保存修改会二次确认；保存中禁止返回。

## 改动文件

新增/修改的主要文件：

- `android/build.gradle.kts`、`android/app/build.gradle.kts`：KSP、Room、测试依赖、versionCode 4 / versionName `0.2b-library-edit`。
- `android/app/src/main/java/com/offlinequiz/app/model/`：`QuizModels.kt`、`QuestionValidator.kt`、`BlankEditor.kt`。
- `android/app/src/main/java/com/offlinequiz/app/data/`：`QuizDatabase.kt`、`LibraryStore.kt`、`LibraryRepository.kt`、`QuestionMapper.kt`、`QuizJson.kt`、`PrivatePdfStore.kt`、`PdfPageRenderer.kt`。
- `android/app/src/main/java/com/offlinequiz/app/viewmodel/`：`LibraryListViewModel.kt`、`LibraryDetailViewModel.kt`、`QuestionEditViewModel.kt`；`ImportViewModel.kt` 扩展保存流程。
- `android/app/src/main/java/com/offlinequiz/app/ui/`：`HomeScreen.kt`、`ImportScreen.kt`、`LibraryDetailScreen.kt`、`QuestionEditScreen.kt`、`PdfPageDialog.kt`、`UiCommon.kt`。
- `MainActivity.kt`、`OfflineQuizApplication.kt`：页面导航、Room/Repository/私有 PDF 存储注入。
- `tools/android-build.ps1`：产物名改为 `offlinequiz-02b-debug.apk`。
- `android/README.md`、`docs/STATUS.md`、本报告、`docs/reports/02b-device-checklist.md`。
- 测试和 schema：`android/app/src/test/` 新增 5 个测试类；`android/app/src/androidTest/` 新增 2 个测试类；`android/app/schemas/.../1.json`。

未修改 PDF 提取/解析主路线；共享 `Model.java`、`QuestionParser.java`、`AndroidPdfExtractor.kt`、`PdfBoxAndroidParser.kt` 保持已验收逻辑（`PdfBoxAndroidParser` 只在现有导入流程中复用）。

## 实际验证

### 共享解析回归

命令：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1
```

结果：`PASS=30 FAIL=0`，退出码 0。包含真实私有样本 `201 questions, 58 underlines` 集成回归，以及跨行、顿号、排版空格、边界歧义等合成 PDF 回归。

### Android JVM 单元测试

命令：

```powershell
$env:JAVA_HOME='F:\New project\work\jdk-21.0.13+9'
$env:ANDROID_HOME='F:\New project\work\android-sdk'
$env:ANDROID_USER_HOME='F:\New project\work\android-user-home'
$env:GRADLE_USER_HOME='F:\New project\work\gradle-user-home'
android\gradlew.bat --project-dir android --no-daemon :app:testDebugUnitTest
```

强制重跑（`--rerun-tasks`）结果：`32 tests / 0 failures / 0 errors / 0 skipped`。

- `QuestionParserTest`：4（原已验收解析回归）
- `QuestionValidatorTest`：8（空题干、重复键、不存在正确项、单选多答案、多选少于两项、填空数量不一致、合法路径、简答空答案）
- `BlankEditorTest`：5（增/删/合并空、空位计数、顺序）
- `QuizJsonTest`：5（选项、字符串列表、填空片段/学习空、来源行、坏 JSON 回退）
- `QuestionMapperTest`：2（字段/证据保留、两库同题号 ID 不同）
- `LibraryRepositoryTest`：8（原始证据、导入 ID 隔离、副本失败、数据库失败回滚、保存失败保留旧内容、确认/编辑状态、结构校验、重命名）

### 构建

- `:app:assembleDebug`：成功。
- `:app:assembleDebugAndroidTest`：成功；7 个仪器测试已编译，**未执行**。
- 强制重跑 `--rerun-tasks :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug`：`BUILD SUCCESSFUL`，79 个任务实际执行。
- `tools/android-build.ps1`：`BUILD SUCCESSFUL`，产出 APK 并写入 `work/android-build/build-summary.txt`。

### APK

- 路径：`outputs/apk/offlinequiz-02b-debug.apk`
- 大小：42,937,117 字节
- SHA-256：`9CE2FA3CA0BA45569244F154DB2DFD2EE8F40E65EF3150F2A0E2A71F481B0575`
- applicationId：`com.offlinequiz.app`
- versionCode：4；versionName：`0.2b-library-edit`
- minSdk 29 / targetSdk 36 / compileSdk 36
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`，与 `offlinequiz-02a-blank-rules-r1-debug.apk` 一致。
- 权限：无 `INTERNET`、无存储/媒体/相机权限；只有 AndroidX 自动生成的 `com.offlinequiz.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（应用内部动态接收器保护，不是危险权限）。
- APK 内 243 个条目，0 个 `.pdf` 条目；未发现 `local-inputs`、`sample.pdf`、`New project`、`F:\New project` 等真实样本/本机路径字符串。
- 旧 APK 全部保留：`offlinequiz-02a-debug.apk`、`offlinequiz-02a-blank-rules-debug.apk`、`offlinequiz-02a-blank-rules-r1-debug.apk`。

## 阻碍

无实现阻碍。当前唯一限制是本机没有安卓设备/模拟器，无法执行 Room/URI/PdfRenderer 仪器测试和真机界面验收；按要求没有为此安装 Android Studio、模拟器镜像或更改系统设置。

## 尚未验证

没有安卓设备/模拟器，因此以下均**未执行**，不能视为通过：

- 7 个仪器测试：Room 内存库计数/隔离、确认更新与 `updatedAt`、事务失败回滚、数据库关闭重开持久化、真实 `content://` 私有副本复制、非 PDF 拒绝、PdfRenderer 单页渲染/越界。
- `outputs/apk/offlinequiz-02b-debug.apk` 在 RMX3800 / Android 16 上的安装、启动、导入真实 201 题样本、保存、重启恢复。
- 真实界面交互：首页多卡片、导入预览、编辑表单、键盘/滚动、对话框、返回确认、Toast/Snackbar。
- 真实 PDF 来源页的渲染效果、页码对应、内存释放和快速翻页。
- 旋转、进程死亡恢复、低存储、权限撤销、损坏 PDF、超大 PDF、其他版式（旋转/多栏/跨页）。
- 深色模式/无障碍/大字体未专门验证；当前主题为浅色。
- 未做 release 构建或正式签名；本阶段按要求只交付 debug APK。
- 未来数据库迁移尚无实际迁移测试（当前只有 v1）。

## 最短手机检查步骤

完整清单见 `docs/reports/02b-device-checklist.md`。最短路径：

1. 安装 02B APK，确认版本 0.2b、无网络/存储权限。
2. 导入真实样本 → 命名保存 → 首页出现题库且题量/待校对正确。
3. 强制停止并重开 → 题库仍在。
4. 打开一题 → 改题干/答案 → 仅保存 → 保存并确认 → 重启后状态仍在。
5. 打开填空 → 添加/删除/合并空 → 故意制造空位数量不一致，确认被拒绝。
6. 点“查看原页” → 显示来源页 → 关闭 → 再次打开正常。
7. 连续导入两次，确认两个独立题库、相同题号不互相覆盖。
8. 有设备时运行 `connectedDebugAndroidTest`，确认 7 个仪器测试通过。

## 边界与停止

- 未进入练习、判分、错题本、统计、简答 n-gram 或备份阶段。
- 未删除题库、未做云同步、未做自动去重合并（符合本阶段范围）。
- 未提交/推送 Git，未发布 APK，未修改项目外设置，未安装系统级工具。
- 真实样本和完整题库只留在忽略目录，未写入 APK 或报告。

完成本报告后停止，等待 Codex 独立复查。真机通过前，状态保持“逻辑/构建就绪、设备未测”。
