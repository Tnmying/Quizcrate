# 阶段 03A 报告：顺序/随机练习与进度保存

日期：2026-11-03。执行者：DSH。状态：`PHASE_03A_READY_FOR_REVIEW`。真机未测。

## 结论

已完成 03A：本地题库的顺序/随机练习、五种题型基础作答、保守判题、简答参考答案+自评、幂等作答记录、固定随机顺序、自动草稿/进度和重启恢复。未进入错题本看板、长期统计、n-gram、备份或发布阶段；未修改已验收的 PDF 解析/仅顿号拆空规则。

## 用户流程

1. 题库详情显示“练习”卡：可练习数量、待校对/结构异常/未知题型数量，以及“去校对待处理题”入口。
2. 有可练题时可开始顺序或随机练习；已有未完成会话时显示“继续练习”和“开始新一轮”。新一轮会新建会话并把旧活动会话标记为旧会话，旧作答记录保留。
3. 练习中显示第 X/N 题、题型、原编号；提交前不显示参考答案、可接受答案、原页或原始带答案证据。
4. 选择/填空/自评/跳过；提交后锁定并显示文字反馈，用户点“下一题”才前进；最后一题完成后显示本轮结果。
5. 自动正确/错误、自评记得/没记住、跳过分开显示和统计。
6. 中途返回/重启恢复最近已落盘的题目、阶段、草稿或反馈；草稿保存失败有中文提示，不假装成功。

## 可练题与固定题目快照

- 只接受五种题型，且 `QuestionValidator` 结构有效。
- 有原始识别异常且 `confirmed=false` 的题排除并计入“待校对”；已确认且结构有效可练习。
- 无原始异常的有效题可直接练习，不强制逐题确认。
- 未知题型、结构错误、判断题非法参考答案排除。
- 创建会话时把当前校对后的题目内容做成快照（题干、选项、答案、可接受答案、简答参考答案、原编号/ID），保存到 `practice_sessions.questionsJson`。之后修改题库不会改变旧会话；历史答案行另存 `referenceSnapshotJson`。
- 顺序按库内 `position`；随机用一轮多次 `shuffled` 一次并持久化，恢复时不重新洗牌，每个题目 ID 只出现一次。

## 判题规则

- 单选：恰好一个用户选项且匹配参考答案。
- 判断：已知对/错值统一（如 `√/对/正确/T`、`×/错/错误/F`）；非法参考答案不可进入练习。
- 多选：用户选择集合与参考答案集合完全相等；少选、多选、错选都错误，无部分分。
- 填空：
  - 按答案顺序逐空比较，空数必须一致；所有空都匹配才整题正确。
  - 参考答案与用户校对的可接受答案都可匹配；可接受答案按空对应，顺序不可错位。
  - 保守规范化：全角 ASCII 转半角、全角/常见空白转空格、首尾 trim、连续空白折叠为单个空格。
  - 不删除所有内部空格、标点、数字、否定词；连续排版空白折叠为单个空格；不使用 NFKC/相似度。`1.5` ≠ `15`，`不能` ≠ `能`，`A B` ≠ `AB`，`甲，乙` 与 `甲,乙` 只做宽度统一而不是删除标点。
  - 大小写保留，不做大小写折叠；`CPU` 与 `cpu` 不视为相同，避免吞掉有意义的大小写差异。
  - 手动合并后的整个参考答案按一个空判题，不再次按顿号拆分。
- 简答：不做自动语义判分；展开参考答案后由用户“记得/没记住”自评。

## 记录、幂等与进度

### Room v2

`QuizDatabase.VERSION = 2`，新增：

- `practice_sessions`：稳定 UUID、libraryId、mode、status、固定题目顺序 JSON、题目快照 JSON、currentIndex、phase、draftJson、draftRevision、createdAt/updatedAt/completedAt。
- `practice_answers`：id、sessionId、questionId、position、kind、userAnswerJson、result、referenceSnapshotJson、idempotencyKey、submittedAt。

索引/约束：`(sessionId, position)` 唯一；`idempotencyKey = "$sessionId:$position"` 唯一；会话 `(libraryId,status)` 索引。答案表外键指向会话。

### 幂等提交

- 每次提交在一个 Room 事务中：校验活动会话和当前索引 → 查 `idempotencyKey` → 不存在才插入 → 同一事务把会话设为 `feedback` 并清空草稿。
- 重试/双击/响应丢失后再次提交同一位置：返回已存储的答案（`alreadyRecorded`），不新增行、不重复记次。
- 内容校验失败/保存异常：返回中文失败，会话仍为 `answering`，输入保留；成功落盘后才显示反馈。
- “下一题”单独事务：必须当前为 `feedback` 且该位置已有答案，才更新索引/完成状态；重复点击无副作用，不会多前进。

### 草稿与恢复

- 输入通过 350ms 去抖保存；每次变更递增 `draftRevision`。
- SQL 更新条件包含 `currentIndex`、`phase='answering'`、`status='active'` 且 `draftRevision < :revision`；旧的延迟保存不能覆盖新草稿。
- 返回时等待一次强制草稿保存；失败留在当前页显示中文反馈，不假装成功。
- 重启/旋转从会话行恢复当前题、阶段、草稿；若阶段为 `feedback`，从 `practice_answers` 恢复已提交反馈。
- 强杀发生在去抖窗口内时只保证最近已完成保存的版本；UI 会显示保存失败，不会假成功。

### 初始会话/新一轮

- 创建会话是事务：先 supersede 同库所有 `active` 会话，再插入新会话；旧会话标记 `superseded`，不删除旧答案。
- 继续练习查询最新的 `active` 会话；完成后状态 `completed`。
- 历史记录保留；本轮结果从答案行汇总，自动/自评/跳过分别计数。

## 实际验证

### 共享解析回归

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/pdf-probe/run-tests.ps1
```

结果：`PASS=30 FAIL=0`，退出码 0，含真实样本 `201 questions, 58 underlines`。

### Android JVM 单测

强制重跑 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`：**BUILD SUCCESSFUL，79 个任务全部执行**。

JUnit XML 汇总：**72 tests / 0 failures / 0 errors / 0 skipped**。

新增/相关测试：

- `PracticeJudgeTest`：8（选择/判断/多选精确集合/填空规范化/可接受答案/顺序/简答无自动判分/未作答）
- `PracticeEligibilityTest`：6（正常题、待校对、已确认、结构错误、未知题型、混合计数）
- `PracticeJsonTest`：3（快照、草稿、用户作答往返、坏 JSON）
- `PracticeRepositoryTest`：13（空库、顺序快照、固定随机、排除、幂等重试、失败重试、空提交拒绝、自评/跳过汇总、草稿乱序、新一轮 supersede、重复前进）
- 原有 02B R1 测试：BlankEditor 10、LibraryRepository 8、QuestionEditSession 5、Mapper 2、Parser 4、Validator 8、Json 5。

### 构建与 APK

- `:app:assembleDebug`：成功。
- `:app:assembleDebugAndroidTest`：成功；13 个仪器测试已编译，**未执行**。
- APK：`outputs/apk/offlinequiz-03a-debug.apk`
- 大小：43,264,793 字节
- SHA-256：`9555F9C707D962A3D7B99BF587C9763C5661DAA499851F774B72A430BC6989A6`
- applicationId：`com.offlinequiz.app`
- versionCode：6；versionName：`0.3a-practice`
- minSdk 29 / targetSdk 36 / compileSdk 36
- 签名：Android Debug，证书 SHA-256 `f99f2262a6af6996372927b50c46b342d3862401f1ef6c2d3b20fef892c5149d`，与 02B R1/02A 一致。
- 权限：无 INTERNET/存储/相机；仅 AndroidX 自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
- APK 243 个条目、0 个 `.pdf` 条目；未发现 `local-inputs`、`sample.pdf`、`New project`、`F:\New project`；旧 APK 全部保留。
- Schema：`android/app/schemas/com.offlinequiz.app.data.QuizDatabase/1.json` 保留，新增 `2.json`。

## 尚未验证

本机没有安卓设备/模拟器，以下均未执行：

- 13 个仪器测试：Room v1→v2 迁移、练习事务/幂等/重开、旧题库升级、PDF 私有副本、PdfRenderer。
- 03A APK 在 RMX3800 / Android 16 的覆盖安装和真实 v1 数据迁移。
- 真实 201 题样本经过练习入口、顺序/随机、五题型作答、草稿恢复、旋转/强杀、低存储失败、结果页。
- UI 自动截图/点击测试未做；判题和记录的纯逻辑/JVM 已测，不等于 Compose 界面实测。
- 随机顺序在真机强杀后的恢复、慢写入时双击/返回、真实保存失败提示未真机验证。
- 其他 PDF 版式、超大题库性能、深色/无障碍未验证。
- 仅 debug 构建，无 release 正式签名。

## 阻碍

无实现阻碍。唯一限制是无安卓设备/模拟器；按要求未安装模拟器镜像、Android Studio 或修改系统设置。

## 最短手机步骤

完整清单见 `docs/reports/03a-device-checklist.md`。最短：

1. 保留旧 02B R1 数据，覆盖安装 03A，确认 versionCode 6、旧题库/题目/确认状态/PDF 路径仍在。
2. 打开题库 → 练习 → 顺序；确认显示五题型、进度、原编号，提交前无答案/原页泄漏。
3. 做一道多选（少选/多选均错）、一道填空（全半角/空格/可接受答案）、一道简答（展开后自评）。
4. 提交后快速再点/旋转/强杀重开：反馈仍在，不重复记次；点“下一题”才前进。
5. 输入草稿等待后强杀重开：草稿恢复；返回时保存失败有提示。
6. 随机练习开始后强杀重开：顺序不重新洗牌；一轮完成显示自动/自评/跳过分开的结果。
7. 开始新一轮：旧会话/历史保留；有设备时运行 `connectedDebugAndroidTest`（13 项）。

## 停止

仅完成 03A。未提交、推送、发布，未进入错题本/n-gram/备份。完成报告后停止，等待用户手动通知 Codex 验收。
