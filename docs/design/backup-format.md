# 离线备份格式与恢复设计（03D1）

日期：2026-11-03。格式版本：`1`。实现：`android/app/src/main/java/com/offlinequiz/app/backup/`。本轮只做格式/读写/校验/恢复计划核心，**未接手机文件选择器、未写 Room、未创建生产恢复入口**。

## 目标与边界

- 备份全部题库、校对后的题目、完整练习会话（进行中/已结束/被替换）、固定题目快照、作答记录、草稿/进度和可用来源 PDF；不导出统计缓存。
- 第一版恢复为“作为新题库副本导入”：生成全新 ID 与计划，现有题库/练习永不被覆盖或清空；真正写库在 03D2。
- 不打包 Room 数据库/WAL，不记录设备绝对路径、私有 PDF 路径或用户名。文件**不加密**；获得备份文件的人可读取其中题库与练习记录，手机导出页需简单说明“含题库和练习记录”。
- 只用 org.json 与标准 `java.util.zip`，无网络/服务/账号/大型依赖。

## ZIP 条目

UTF-8，ZIP（DEFLATED），条目名固定、相对、`/` 分隔：

```text
manifest.json
libraries/<ordinal>/questions.ndjson
libraries/<ordinal>/sessions.ndjson
libraries/<ordinal>/answers.ndjson
libraries/<ordinal>/resources/source.pdf   # 可选
```

- `<ordinal>` 为 0 起连续序号，只用于路径，不是题库 ID。
- NDJSON：每行一个 JSON 对象，UTF-8，末尾换行；空行拒绝。
- `manifest.json` 必须是 ZIP 的第一个条目；读取不落盘、不解压到目录。

## manifest.json

```json
{
  "format": "offlinequiz-backup",
  "formatVersion": 1,
  "createdAt": 1790000000000,
  "databaseVersion": 3,
  "libraries": [
    {
      "ordinal": 0,
      "id": "旧题库UUID（仅用于一致性校验，恢复时重新生成）",
      "name": "题库名",
      "createdAt": 1, "updatedAt": 2,
      "sourceFileName": "demo.pdf",
      "sourcePageCount": 3,
      "originalSchemaVersion": 3,
      "questionCount": 6, "sessionCount": 3, "answerCount": 9,
      "pdf": {"included": true, "entry": "libraries/0/resources/source.pdf",
              "byteSize": 1234, "sha256": "64位小写十六进制"}
    }
  ],
  "entries": [
    {"path": "libraries/0/questions.ndjson", "kind": "questions", "ordinal": 0, "recordCount": 6},
    {"path": "libraries/0/sessions.ndjson", "kind": "sessions", "ordinal": 0, "recordCount": 3},
    {"path": "libraries/0/answers.ndjson", "kind": "answers", "ordinal": 0, "recordCount": 9},
    {"path": "libraries/0/resources/source.pdf", "kind": "pdf", "ordinal": 0, "recordCount": 0}
  ]
}
```

- `formatVersion` 与 Room 的 `databaseVersion` **独立**：格式版本决定可读性，数据库版本只作诊断；恢复写入当前数据库版本。
- `pdf.included=false` 表示未包含来源 PDF（导出时源文件缺失或未取到）。恢复后该题库仍可用，原页查看标记为不可用；**声明 included=true 但条目缺失/损坏/大小或 SHA-256 不符必须拒绝**，不静默降级。
- `sourceFileName` 只存文件名（无 `/`、`\`、`:`），不含目录。
- 不写 `pdfRelativePath`；03D2 复制资源后使用新路径。

## 记录字段

### questions.ndjson

一行一个题目，字段完整保留业务数据，不做“只导出当前题”裁剪：

`id, position, type, originalNumber, numberValue, sectionTitle, stem, stemWithAnswer, originalSentence, referenceAnswer, optionRawText, optionsJson, answers[], acceptableAnswersJson, sourcePages[], sourceLinesJson, blanksJson, learningBlankCount, originalReviewReasons[], originalNeedsReview, originalStem, originalOptionsJson, originalAnswers[], originalBlanksJson, confirmed, confirmedAt, editedAt`

- 嵌套证据（options/sourceLines/blanks/acceptableAnswers/originalOptions/originalBlanks）使用应用现有 `QuizJson` 的**规范 JSON 字符串**，读取时重新编码比对；非规范/损坏嵌套 JSON 拒绝。这样完整保留空映射、顿号子空、可接受答案、诊断与原文证据，又不复制一套容易漂移的嵌套解析器。
- `type` 允许五种题型与 `unknown`；待校对题（`confirmed=false`）合法，不因“不可练习”拒绝。
- 缺失/损坏的旧题目快照情况在 session/answer 中处理，不在题目记录里伪造。

### sessions.ndjson

`id, mode, status, questions[], currentIndex, phase, draft, draftRevision, createdAt, updatedAt, completedAt, source`

- `questions[]` 为固定 `PracticeQuestionSnapshot`：`questionId, libraryId, position, type, originalNumber, stem, optionsJson, answers[], acceptableAnswersJson, referenceAnswer, editedAt`；数组顺序即本轮顺序快照。
- 三种状态 `active/completed/superseded` 与阶段 `answering/feedback/completed` 均保留；`completed` 当且仅当 `phase=completed` 且 `completedAt` 非空。
- 草稿只在 `answering` 阶段允许非空；`feedback/completed` 草稿必须为空。
- 每库最多一个 `active` 会话；已完成会话 `currentIndex == questions.size`，其余 `0..size-1`。
- 会话快照的 `questionId/position` 必须对应本库题目；同一会话不重复题目。

### answers.ndjson

`id, sessionId, questionId, position, kind, result, userAnswer{selectedKeys,blankAnswers,text}, submittedAt, referenceSnapshot`

- `referenceSnapshot` 为提交时的历史快照，或 `null`。**null 表示旧记录快照不可用**（原库中缺失/损坏），恢复时按既有兼容行为显示“快照不可用”，不伪造内容。
- **位置语义（R1）**：`answer.position` 是本轮 `session.questions` 的**索引**；`referenceSnapshot.position` 是题目在原题库中的**位置**。二者在错题子集/随机重排时通常不同，不得要求相等。
- 若存在快照，必须与会话固定快照 `session.questions[answer.position]` 完全一致（题目身份/题干/选项/答案/可接受答案/参考内容/题库位置/`editedAt`），`libraryId` 属于该库；不能用当前已编辑的题库题目补旧记录。
- 校验：会话存在、`position` 在会话题目范围内、`questionId == session.questions[position].questionId`、同一 `(sessionId, position)` 唯一、`kind/result` 成对合法。
- **完整进度（R1）**：按 phase/currentIndex 核对作答位置集合，不只检查已有答案：
  - `answering`：恰好 `0 .. currentIndex-1`（当前题尚未提交；`currentIndex=0` 时为空集合法）；
  - `feedback`：恰好 `0 .. currentIndex`（当前题必须已有提交记录）；
  - `completed`：恰好全部 `0 .. questions.size-1`；
  - `superseded`：保留其当时 phase，按上述同一规则；缺失、断档、超前（含 `answering` 阶段在 currentIndex 处已有答案）一律拒绝。
  - 允许 `referenceSnapshot=null`（旧快照不可用），但不允许缺失对应位置的作答本身；不补假作答、不丢现有记录、不静默重置进度。
- 自动/自评/跳过的结果语义原样保留，不进入统计缓存。

## 校验与限制

写入前与读取后都跑同一套跨记录校验；读取必须完整通过才生成 `BackupBundle`，之后才允许生成恢复计划。

| 限制（默认） | 值 |
| --- | --- |
| 题库数 | 200 |
| ZIP 条目数（含 manifest） | 2000 |
| manifest 字节 | 2 MiB |
| 单 JSON 条目解压字节 | 64 MiB |
| 单行字节 | 8 MiB |
| 单字符串 UTF-8 字节 | 1 MiB |
| 总解压字节 | 1 GiB |
| 每库题目/会话/作答 | 50,000 / 50,000 / 500,000 |
| PDF 字节 | 256 MiB |

- 所有字节按**实际流式读取量**统计，不信 `ZipEntry.size` 或 manifest 数字；测试可注入更小限制。
- 必须拒绝：非 ZIP/空、manifest 不在首位、未来格式、重复/未声明/缺失条目、绝对路径或 `..`/`\`/盘符、重复 ZIP 条目、损坏 JSON/UTF-8、重复 ID、悬空引用、非法索引、损坏/谎报大小的 PDF、超过限制、取消中断。
- 完整性：每个 ZIP 条目由 ZIP CRC32 在 `closeEntry` 校验；PDF 另有 manifest 中的 SHA-256 与 `%PDF-` 文件头校验；manifest 记录数与实际行数交叉核对。未在 manifest 中放逐条 SHA-256（单遍流式、manifest 先写），CRC + 严格解析 + 计数已覆盖“用户选到损坏文件”的场景；不声称能防主动伪造。
- 未知对象字段忽略（向前兼容）；未知顶层条目/未知未来格式拒绝；缺字段/类型错误拒绝；不静默丢记录凑成功。

## 恢复为新副本计划

`RestorePlanner.plan(bundle, idFactory, targetSchemaVersion)` 只产出内存对象，不写 Room/文件：

- 注入的 `BackupIdFactory` 为每个题库/题目/会话/作答生成新 ID；拒绝空/重复/与旧 ID 相同的生成值。
- 重写所有引用：`libraryId`、会话快照的 `questionId/libraryId`、作答的 `sessionId/questionId`、历史快照的 `questionId/libraryId`；幂等键改为 `"<newSessionId>:<position>"`。
- 保留原时间、模式、状态、阶段、来源、草稿、`completedAt`、结果类别与用户答案；`LibraryRecord.schemaVersion` 写目标数据库版本。
- `pdfRelativePath` 设为 `libraries/<newLibraryId>/source.pdf`，计划同时给出备份内条目名/大小/SHA-256；03D2 复制后再写库。
- `RestoreSummary` 提供题库/题目/会话/作答数、PDF 包含/缺失数、待校对题数、三种会话数，可先预览后确认。
- 重复导入各自生成独立 ID 集，不关联既有同 ID 题库；每库最多一个活动会话随新副本保留。

## 失败与取消边界

- 导出 `BackupWriter.write` 是 `suspend`，在题库/记录/PDF 分块处检查协程取消；取消或异常会抛出，可能留下**部分输出**。03D2 必须写入临时文件，成功后才发布（重命名/交给系统），失败删除临时文件；本核心从不写现有题库。
- 读取失败只抛 `BackupException`（带机器可读 `BackupError` 与中文消息），不返回半份 bundle/计划；不自动解压、不写 Room。
- 当前无生产恢复按钮；不创建“恢复成功”提示。

## 03D2 接入计划

1. **读取快照**：实现 `BackupSource`（`libraries()` 返回 `BackupLibraryHeader` 含记录与计数；`questions/sessions/answers` 按库读取；`openPdf` 返回可重复打开的流）。需要新增/复用 DAO：
   - 全部题库记录；
   - 按 libraryId 取全部题目；
   - 按 libraryId 取**全部会话**（不能只取 active，需包含 completed/superseded）；
   - 按 libraryId 取全部作答及 `referenceSnapshotJson`（join/关系查询）；
   - 各库计数。
   一致性：在一个 Room 事务/一致性读中取计数与记录；PDF 先复制到临时稳定文件或加锁，避免导出期间被替换（导出会读两遍做 SHA-256）。
2. **导出**：用户选保存位置 → 临时文件 → `BackupWriter.write` → 成功后交付；失败/取消删除临时文件。
3. **导入预览**：`BackupReader.read` → `RestorePlanner.plan` → 展示 `RestoreSummary`（题库/题目/会话/作答、PDF 包含/缺失、待校对）；用户确认前不写库。
4. **恢复事务**：为新题库生成 ID → 复制 PDF 到 `files/libraries/<newId>/source.pdf` → 单事务插入 library/questions/sessions/answers；失败回滚 DB 并删除新 PDF 目录；不覆盖旧库。需要新增事务 DAO（插入聚合/批量），本轮不改真实数据路径。
5. 仍不加密；UI 只做简短说明；恢复后来源页查看按 PDF 是否包含决定可用性。

## 测试覆盖

- `BackupCoreTest`（3 项）：两题库、五题型、顿号子空/可接受答案、待校对、改题前历史、缺失历史快照、三种会话状态、自动/自评/跳过、未完成草稿、可选 PDF；导出→读取→校验→新 ID 计划；重复导入隔离；PDF 字节一致。
- `BackupRejectionTest`（18 项）：未来版本、重复题目/清单/ZIP 条目、悬空引用、非法索引、损坏 PDF、`..`/绝对路径、未知条目、谎报 PDF 大小、超限、截断/非法 UTF-8 JSON、缺条目、CRC 不一致、截断 ZIP、取消导出。
- `BackupSessionSemanticsTest`（12 项）：真实 `PracticeRepository.startWrongbook` 题库第 6 题作为本轮第 1 题的错题子集、真实随机重排整轮、顺序样例；三阶段/superseded 的完整作答集合正反例（缺少、断档、超前、feedback 无提交）；`referenceSnapshot=null` 允许；快照内容与会话不一致拒绝；`snapshot.position` 与 `answer.position` 不同仍合法。

## 03D2 手机入口与 Room/文件一致性（已接入）

### 手机流程

- 首页“备份与恢复”进入独立路由 `backup`；导出用系统 `CreateDocument("application/zip")`，恢复用 `OpenDocument()`；不申请广泛存储权限，不读写任意本机路径。
- 导出：`BackupService.prepareExport` 在 IO 线程读取一致性快照 → 写应用私有缓存临时 ZIP → `BackupReader` 回读完整校验；**成功后才**由控制器调用 `openTarget()` 打开系统目标，`publishExport` 以 8 KiB 分块复制并在每块前后响应取消。生成/校验失败不打开目标；复制失败/取消明确提示“导出未完成，目标文件可能不完整”，不显示成功；系统 DocumentProvider 不保证原子替换，代码不删除用户原有目标文件。
- 请求隔离：`BackupController` 为每次 export/prepare/confirm 分配独立 requestId + `AtomicBoolean` 取消标记 + Job；旧请求的迟到成功/失败/取消/清理只在身份仍匹配时更新 state。`prepare` 用 `onPrepared` 回调登记临时结果，交付时已取消则清理；提交取消/失败先检查 `PreparedRestore.committed`，已提交不误报未写入、不删 PDF、不重复插入。
- 就绪预览取消：`cancelPreview()` 不依赖仍在运行的活动 Job；即使 prepare 已完成、`active` 已清空，也作废 `PreparedRestore`、删除临时 ZIP、回 Idle 并阻止 `confirm()`；重复取消幂等，取消后可重新准备/导出，旧回调不清理新预览。`Committing` 期间仍拒绝取消。
- 导出关闭：成功路径必须显式 `target.close()` 成功后才进入 Success；close 失败按“导出未完成”错误处理。`closeQuietly` 只用于已失败/取消后的兜底清理。系统目标在 `NonCancellable` 中打开并写入 `AtomicReference`，取消发生在打开交付边界时 finally 关闭该句柄，不泄漏、不重复发布。
- 恢复：先把所选 URI 复制到应用缓存临时文件（`MAX_BACKUP_FILE_BYTES=64 MiB` 实际字节上限），后台 `BackupReader`（可协作取消）读取/校验/生成计划；展示题库/题目/练习记录/PDF 包含与缺失/待校对摘要，明确“作为新副本导入，现有题库不变”；用户确认后才提交。
- 取消选择/预览/校验不写业务数据；确认前取消删除临时文件。同操作重复点击由 ViewModel 状态机拦截；确认后进入 `Committing`，按钮/返回/系统返回统一拦截，禁止离页。
- 旋转由 ViewModel 保持状态；进程重启不持久化恢复请求，`Application.onCreate` 只清理缓存 `backup/` 临时文件，绝不扫描/删除已有题库 PDF；不会自动重放恢复。
- 成功后可从首页打开新题库；历史/统计/错题/活动练习草稿/反馈/随机顺序由 Room 中恢复的原始记录继续。

### 一致性读取与恢复写入

- `BackupDao.readAll()` 在**单个 Room 事务**中读取 libraries/questions/practice_sessions/practice_answers 全表（所有状态会话，不只 active），`RoomBackupDataStore.readSnapshot` 分组为完整聚合。
- 恢复不走普通 `startSession`（那会 supersede 活动会话）：`RestorePlanner` 已保留原 status/phase/currentIndex/草稿/快照/幂等键，`RoomBackupDataStore.insert` 在**单个事务**中按 libraries → questions → sessions → answers 顺序插入；所有 `@Insert` 为 `ABORT`，任何 PK/FK/唯一键冲突整次回滚，不使用 REPLACE/清库/逐库提交。
- 新 ID 由 UUID 生成器产生；插入前逐项查询现有 DB ID，冲突明确失败（不重用、不覆盖）；文件目录 `files/libraries/<newId>` 已存在则失败（不删除未知目录）。
- PDF 顺序：先按计划从**同一个已验证临时 ZIP** 读取 PDF → 写 `<newId>/source.pdf.part` → 校验实际字节/SHA-256 → 改名 `source.pdf` → 再提交 DB；DB 因此不会引用未就绪 PDF。缺 PDF 的计划 `pdf=null`，仍恢复数据并标记原页不可用；声明包含但条目缺失/损坏/大小或哈希不符则整次失败。
- 失败补偿：提交前失败只递归删除本次新建的 `stagedDirs` 与临时 ZIP；DB 提交一旦成功，`PreparedRestore.committed=true` 在 `NonCancellable` 中设置，迟到取消/回调不会删除已提交 PDF，也不谎报未写入。
- 异常/强杀：进程启动清理仅限 `cacheDir/backup/` 文件；`files/libraries/<id>` 中未被 DB 引用的目录由既有 `PrivatePdfStore.cleanupOrphans`（仅删除超过 1 小时的孤儿目录）处理，不扫描删除已有题库资源。

### 手机端限制

- `ANDROID_BACKUP_LIMITS`：200 库、2000 条目、2 MiB manifest、32 MiB/JSON 条目、2 MiB/行、256 KiB/字符串、**64 MiB 总解压**、2 万题目/会话、20 万作答/库、32 MiB/PDF；所选备份文件复制上限 64 MiB。校验后的计划整体在内存中，因此不把核心的 1 GiB 上限搬到手机。
- 读取在 IO 线程；`BackupReader` 每个条目/记录/PDF 分块可协作取消；UI 预览取消置位并清理临时文件，不继续在后台生成计划。
- Room schema 仍为 3；新增 `BackupDao` 不升版本，无迁移。

### 03D2 测试

- JVM `BackupServiceTest`（7）：导出→读取→预览→提交、重复导入独立、非法输入、校验中取消、DB 插入失败回滚/清理、提交后迟到取消保留 PDF、缺 PDF。
- JVM `BackupServiceIntegrationTest`（1）：两库、五题型、待校对、改题前历史、缺失旧快照、三状态会话、草稿/反馈、可选 PDF，经服务 export→prepare→commit；断言原快照不变、记录/引用/新活动练习/重复导入独立。
- JVM `BackupControllerTest`（9）：目标只在生成/校验后打开；A 取消→B 启动→A 迟到完成/交付不影响 B 且清理 A 资源；确认重复只提交一次；提交已 committed 后迟到取消仍成功；提交失败可继续操作；就绪预览取消回 Idle 并阻止提交、重复取消幂等、可重新准备；目标 close 失败不报成功；正常 close 只一次；打开交付时取消的句柄被关闭。
- JVM `BackupRouteTest`（1）：入口路由持久化/返回。
- 仪器 `BackupRoomDatabaseTest`（3）：一致性快照读取全状态、聚合插入保留原库、重复 ID 整次回滚；**仅编译，未执行**（无设备）。
