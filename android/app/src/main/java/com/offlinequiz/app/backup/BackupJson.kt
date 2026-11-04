package com.offlinequiz.app.backup

import com.offlinequiz.app.data.QuizJson
import com.offlinequiz.app.model.LibraryRecord
import com.offlinequiz.app.model.PracticeDraft
import com.offlinequiz.app.model.PracticeQuestionSnapshot
import com.offlinequiz.app.model.PracticeRecordedAnswer
import com.offlinequiz.app.model.PracticeSessionRecord
import com.offlinequiz.app.model.PracticeUserAnswer
import com.offlinequiz.app.model.StoredQuestion
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned UTF-8 JSON codec for the manifest and NDJSON records.
 *
 * Required fields/types/enums are checked here; nested question evidence keeps the app's existing
 * canonical [QuizJson] encoding and is validated by re-encoding (non-canonical or malformed nested
 * JSON is rejected). Unknown object fields are ignored; unknown ZIP entries/newer formats are
 * rejected before use.
 */
object BackupJson {

    fun encodeManifest(manifest: BackupManifest): String {
        val root = JSONObject()
            .put("format", BACKUP_FORMAT_NAME)
            .put("formatVersion", BACKUP_FORMAT_VERSION)
            .put("createdAt", manifest.createdAt)
            .put("databaseVersion", manifest.databaseVersion)
        val libraries = JSONArray()
        for (meta in manifest.libraries) {
            libraries.put(
                JSONObject()
                    .put("ordinal", meta.ordinal)
                    .put("id", meta.record.id)
                    .put("name", meta.record.name)
                    .put("createdAt", meta.record.createdAt)
                    .put("updatedAt", meta.record.updatedAt)
                    .put("sourceFileName", meta.record.sourceFileName)
                    .put("sourcePageCount", meta.record.sourcePageCount)
                    .put("originalSchemaVersion", meta.record.schemaVersion)
                    .put("questionCount", meta.questionCount)
                    .put("sessionCount", meta.sessionCount)
                    .put("answerCount", meta.answerCount)
                    .put(
                        "pdf",
                        meta.pdf?.let { pdf ->
                            JSONObject()
                                .put("included", true)
                                .put("entry", pdf.entry)
                                .put("byteSize", pdf.byteSize)
                                .put("sha256", pdf.sha256)
                        } ?: JSONObject().put("included", false)
                    )
            )
        }
        root.put("libraries", libraries)
        val entries = JSONArray()
        for (entry in manifest.entries) {
            entries.put(
                JSONObject()
                    .put("path", entry.path)
                    .put("kind", entry.kind.name.lowercase())
                    .put("ordinal", entry.ordinal)
                    .put("recordCount", entry.recordCount)
            )
        }
        root.put("entries", entries)
        return root.toString()
    }

    fun decodeManifest(raw: String, limits: BackupLimits): BackupManifest = guarded {
        val root = JSONObject(raw)
        val format = root.str("format", 64)
        if (format != BACKUP_FORMAT_NAME) fail(BackupError.NOT_A_BACKUP, "这不是 OfflineQuiz 备份文件")
        val version = root.int("formatVersion")
        if (version > BACKUP_FORMAT_VERSION) {
            fail(
                BackupError.FUTURE_VERSION,
                "备份格式版本 $version 高于当前支持的 $BACKUP_FORMAT_VERSION，请升级应用后再恢复"
            )
        }
        if (version < 1) fail(BackupError.UNSUPPORTED_VERSION, "备份格式版本无效：$version")

        val createdAt = root.long("createdAt")
        val databaseVersion = root.int("databaseVersion")
        if (createdAt < 0) fail(BackupError.BAD_FIELD, "备份创建时间无效")
        if (databaseVersion < 1) fail(BackupError.BAD_FIELD, "备份数据库版本无效")

        val librariesArray = root.array("libraries")
        if (librariesArray.length() > limits.maxLibraries) {
            fail(BackupError.TOO_LARGE, "备份中的题库数量超过上限 ${limits.maxLibraries}")
        }
        val metas = (0 until librariesArray.length()).map { index ->
            val item = librariesArray.getJSONObject(index)
            val ordinal = item.int("ordinal")
            if (ordinal != index) fail(BackupError.BAD_INDEX, "题库序号必须从 0 连续排列")
            val id = item.str("id", 128)
            if (id.isBlank()) fail(BackupError.BAD_FIELD, "题库标识为空")
            val name = item.str("name", limits.maxStringBytes)
            val created = item.long("createdAt")
            val updated = item.long("updatedAt")
            if (created < 0 || updated < created) fail(BackupError.BAD_FIELD, "题库时间无效")
            val sourceFileName = item.str("sourceFileName", 512)
            if (sourceFileName.contains('/') || sourceFileName.contains('\\') ||
                sourceFileName.contains(':')
            ) {
                fail(BackupError.BAD_PATH, "来源文件名不能包含路径")
            }
            val pageCount = item.int("sourcePageCount")
            if (pageCount < 0) fail(BackupError.BAD_FIELD, "来源页数不能为负")
            val schemaVersion = item.int("originalSchemaVersion")
            if (schemaVersion < 1) fail(BackupError.BAD_FIELD, "题库结构版本无效")
            val questionCount = item.int("questionCount")
            val sessionCount = item.int("sessionCount")
            val answerCount = item.int("answerCount")
            if (questionCount < 0 || questionCount > limits.maxQuestionsPerLibrary) {
                fail(BackupError.TOO_LARGE, "题库题目数量超过上限")
            }
            if (sessionCount < 0 || sessionCount > limits.maxSessionsPerLibrary) {
                fail(BackupError.TOO_LARGE, "练习会话数量超过上限")
            }
            if (answerCount < 0 || answerCount > limits.maxAnswersPerLibrary) {
                fail(BackupError.TOO_LARGE, "作答记录数量超过上限")
            }
            val pdfObject = item.getJSONObject("pdf")
            val pdf = if (pdfObject.bool("included")) {
                val entry = pdfObject.str("entry", 300)
                if (entry != pdfEntry(ordinal)) fail(BackupError.BAD_PATH, "PDF 条目名不符合格式：$entry")
                val byteSize = pdfObject.long("byteSize")
                if (byteSize <= 0 || byteSize > limits.maxPdfBytes) {
                    fail(BackupError.TOO_LARGE, "PDF 大小超出上限")
                }
                val sha = pdfObject.str("sha256", 64)
                if (!sha.matches(Regex("[0-9a-f]{64}"))) fail(BackupError.BAD_FIELD, "PDF 校验值无效")
                BackupPdfInfo(entry, byteSize, sha)
            } else {
                null
            }
            BackupLibraryMeta(
                ordinal = ordinal,
                record = LibraryRecord(
                    id = id,
                    name = name,
                    createdAt = created,
                    updatedAt = updated,
                    sourceFileName = sourceFileName,
                    sourcePageCount = pageCount,
                    pdfRelativePath = null,
                    schemaVersion = schemaVersion
                ),
                questionCount = questionCount,
                sessionCount = sessionCount,
                answerCount = answerCount,
                pdf = pdf
            )
        }
        if (metas.map { it.record.id }.toSet().size != metas.size) {
            fail(BackupError.DUPLICATE_ID, "备份中存在重复的题库标识")
        }

        val entriesArray = root.array("entries")
        if (entriesArray.length() > limits.maxEntries - 1) {
            fail(BackupError.TOO_LARGE, "备份条目数量超过上限")
        }
        val entries = (0 until entriesArray.length()).map { index ->
            val item = entriesArray.getJSONObject(index)
            val path = item.str("path", 300)
            if (!isSafeEntryPath(path)) fail(BackupError.BAD_PATH, "备份条目路径不安全：$path")
            val kind = when (item.str("kind", 32)) {
                "questions" -> BackupEntryKind.QUESTIONS
                "sessions" -> BackupEntryKind.SESSIONS
                "answers" -> BackupEntryKind.ANSWERS
                "pdf" -> BackupEntryKind.PDF
                else -> fail(BackupError.BAD_FIELD, "未知的备份条目类型")
            }
            val ordinal = item.int("ordinal")
            if (ordinal !in metas.indices) fail(BackupError.BAD_INDEX, "备份条目指向不存在的题库")
            val expectedPath = when (kind) {
                BackupEntryKind.QUESTIONS -> questionsEntry(ordinal)
                BackupEntryKind.SESSIONS -> sessionsEntry(ordinal)
                BackupEntryKind.ANSWERS -> answersEntry(ordinal)
                BackupEntryKind.PDF -> pdfEntry(ordinal)
            }
            if (path != expectedPath) fail(BackupError.BAD_PATH, "备份条目名不符合格式：$path")
            val recordCount = item.int("recordCount")
            val meta = metas[ordinal]
            val expectedCount = when (kind) {
                BackupEntryKind.QUESTIONS -> meta.questionCount
                BackupEntryKind.SESSIONS -> meta.sessionCount
                BackupEntryKind.ANSWERS -> meta.answerCount
                BackupEntryKind.PDF -> 0
            }
            if (recordCount != expectedCount) {
                fail(BackupError.BAD_FIELD, "条目记录数与清单不一致：$path")
            }
            BackupEntryRef(path, kind, ordinal, recordCount)
        }
        validateEntrySet(metas, entries)
        BackupManifest(createdAt, databaseVersion, metas, entries)
    }

    fun encodeQuestion(question: StoredQuestion): JSONObject = JSONObject()
        .put("id", question.id)
        .put("position", question.position)
        .put("type", question.type)
        .put("originalNumber", question.originalNumber ?: JSONObject.NULL)
        .put("numberValue", question.numberValue ?: JSONObject.NULL)
        .put("sectionTitle", question.sectionTitle ?: JSONObject.NULL)
        .put("stem", question.stem)
        .put("stemWithAnswer", question.stemWithAnswer ?: JSONObject.NULL)
        .put("originalSentence", question.originalSentence ?: JSONObject.NULL)
        .put("referenceAnswer", question.referenceAnswer ?: JSONObject.NULL)
        .put("optionRawText", question.optionRawText ?: JSONObject.NULL)
        .put("optionsJson", QuizJson.encodeOptions(question.options))
        .put("answers", JSONArray(question.answers))
        .put("acceptableAnswersJson", QuizJson.encodeStringLists(question.acceptableAnswers))
        .put("sourcePages", JSONArray(question.sourcePages))
        .put("sourceLinesJson", QuizJson.encodeSourceLines(question.sourceLines))
        .put("blanksJson", QuizJson.encodeBlanks(question.blanks))
        .put("learningBlankCount", question.learningBlankCount)
        .put("originalReviewReasons", JSONArray(question.originalReviewReasons))
        .put("originalNeedsReview", question.originalNeedsReview)
        .put("originalStem", question.originalStem)
        .put("originalOptionsJson", QuizJson.encodeOptions(question.originalOptions))
        .put("originalAnswers", JSONArray(question.originalAnswers))
        .put("originalBlanksJson", QuizJson.encodeBlanks(question.originalBlanks))
        .put("confirmed", question.confirmed)
        .put("confirmedAt", question.confirmedAt ?: JSONObject.NULL)
        .put("editedAt", question.editedAt ?: JSONObject.NULL)

    fun decodeQuestion(obj: JSONObject, libraryId: String, limits: BackupLimits): StoredQuestion =
        StoredQuestion(
            id = obj.str("id", 128),
            libraryId = libraryId,
            position = obj.nonNegativeInt("position"),
            type = obj.str("type", 64),
            originalNumber = obj.strOrNull("originalNumber", limits.maxStringBytes),
            numberValue = obj.intOrNull("numberValue"),
            sectionTitle = obj.strOrNull("sectionTitle", limits.maxStringBytes),
            stem = obj.str("stem", limits.maxStringBytes),
            stemWithAnswer = obj.strOrNull("stemWithAnswer", limits.maxStringBytes),
            originalSentence = obj.strOrNull("originalSentence", limits.maxStringBytes),
            referenceAnswer = obj.strOrNull("referenceAnswer", limits.maxStringBytes),
            optionRawText = obj.strOrNull("optionRawText", limits.maxStringBytes),
            options = nested(
                obj.str("optionsJson", limits.maxStringBytes),
                QuizJson::decodeOptions,
                QuizJson::encodeOptions,
                "optionsJson"
            ),
            answers = obj.strings("answers", limits),
            acceptableAnswers = nested(
                obj.str("acceptableAnswersJson", limits.maxStringBytes),
                QuizJson::decodeStringLists,
                QuizJson::encodeStringLists,
                "acceptableAnswersJson"
            ),
            sourcePages = obj.ints("sourcePages"),
            sourceLines = nested(
                obj.str("sourceLinesJson", limits.maxStringBytes),
                QuizJson::decodeSourceLines,
                QuizJson::encodeSourceLines,
                "sourceLinesJson"
            ),
            blanks = nested(
                obj.str("blanksJson", limits.maxStringBytes),
                QuizJson::decodeBlanks,
                QuizJson::encodeBlanks,
                "blanksJson"
            ),
            learningBlankCount = obj.nonNegativeInt("learningBlankCount"),
            originalReviewReasons = obj.strings("originalReviewReasons", limits),
            originalNeedsReview = obj.bool("originalNeedsReview"),
            originalStem = obj.str("originalStem", limits.maxStringBytes),
            originalOptions = nested(
                obj.str("originalOptionsJson", limits.maxStringBytes),
                QuizJson::decodeOptions,
                QuizJson::encodeOptions,
                "originalOptionsJson"
            ),
            originalAnswers = obj.strings("originalAnswers", limits),
            originalBlanks = nested(
                obj.str("originalBlanksJson", limits.maxStringBytes),
                QuizJson::decodeBlanks,
                QuizJson::encodeBlanks,
                "originalBlanksJson"
            ),
            confirmed = obj.bool("confirmed"),
            confirmedAt = obj.longOrNull("confirmedAt"),
            editedAt = obj.longOrNull("editedAt")
        )

    fun encodeSnapshot(snapshot: PracticeQuestionSnapshot): JSONObject = JSONObject()
        .put("questionId", snapshot.questionId)
        .put("libraryId", snapshot.libraryId)
        .put("position", snapshot.position)
        .put("type", snapshot.type)
        .put("originalNumber", snapshot.originalNumber ?: JSONObject.NULL)
        .put("stem", snapshot.stem)
        .put("optionsJson", QuizJson.encodeOptions(snapshot.options))
        .put("answers", JSONArray(snapshot.answers))
        .put("acceptableAnswersJson", QuizJson.encodeStringLists(snapshot.acceptableAnswers))
        .put("referenceAnswer", snapshot.referenceAnswer ?: JSONObject.NULL)
        .put("editedAt", snapshot.editedAt ?: JSONObject.NULL)

    fun decodeSnapshot(obj: JSONObject, limits: BackupLimits): PracticeQuestionSnapshot =
        PracticeQuestionSnapshot(
            questionId = obj.str("questionId", 128),
            libraryId = obj.str("libraryId", 128),
            position = obj.nonNegativeInt("position"),
            type = obj.str("type", 64),
            originalNumber = obj.strOrNull("originalNumber", limits.maxStringBytes),
            stem = obj.str("stem", limits.maxStringBytes),
            options = nested(
                obj.str("optionsJson", limits.maxStringBytes),
                QuizJson::decodeOptions,
                QuizJson::encodeOptions,
                "optionsJson"
            ),
            answers = obj.strings("answers", limits),
            acceptableAnswers = nested(
                obj.str("acceptableAnswersJson", limits.maxStringBytes),
                QuizJson::decodeStringLists,
                QuizJson::encodeStringLists,
                "acceptableAnswersJson"
            ),
            referenceAnswer = obj.strOrNull("referenceAnswer", limits.maxStringBytes),
            editedAt = obj.longOrNull("editedAt")
        )

    fun encodeSession(session: PracticeSessionRecord): JSONObject {
        val questions = JSONArray()
        for (snapshot in session.questions) questions.put(encodeSnapshot(snapshot))
        return JSONObject()
            .put("id", session.id)
            .put("mode", session.mode)
            .put("status", session.status)
            .put("questions", questions)
            .put("currentIndex", session.currentIndex)
            .put("phase", session.phase)
            .put("draft", encodeDraft(session.draft))
            .put("draftRevision", session.draftRevision)
            .put("createdAt", session.createdAt)
            .put("updatedAt", session.updatedAt)
            .put("completedAt", session.completedAt ?: JSONObject.NULL)
            .put("source", session.source)
    }

    fun decodeSession(obj: JSONObject, libraryId: String, limits: BackupLimits): PracticeSessionRecord {
        val questionsArray = obj.array("questions")
        if (questionsArray.length() > limits.maxQuestionsPerLibrary) {
            fail(BackupError.TOO_LARGE, "会话题目数量超过上限")
        }
        val questions = (0 until questionsArray.length()).map {
            decodeSnapshot(questionsArray.getJSONObject(it), limits)
        }
        return PracticeSessionRecord(
            id = obj.str("id", 128),
            libraryId = libraryId,
            mode = obj.str("mode", 32),
            status = obj.str("status", 32),
            questions = questions,
            currentIndex = obj.int("currentIndex"),
            phase = obj.str("phase", 32),
            draft = decodeDraft(obj.getJSONObject("draft"), limits),
            draftRevision = obj.long("draftRevision"),
            createdAt = obj.long("createdAt"),
            updatedAt = obj.long("updatedAt"),
            completedAt = obj.longOrNull("completedAt"),
            source = obj.str("source", 32)
        )
    }

    fun encodeAnswer(answer: BackupAnswer): JSONObject = JSONObject()
        .put("id", answer.answer.id)
        .put("sessionId", answer.answer.sessionId)
        .put("questionId", answer.answer.questionId)
        .put("position", answer.answer.position)
        .put("kind", answer.answer.kind)
        .put("result", answer.answer.result)
        .put("userAnswer", encodeUserAnswer(answer.answer.userAnswer))
        .put("submittedAt", answer.answer.submittedAt)
        .put("referenceSnapshot", answer.referenceSnapshot?.let { encodeSnapshot(it) } ?: JSONObject.NULL)

    fun decodeAnswer(obj: JSONObject, limits: BackupLimits): BackupAnswer {
        val snapshot = if (obj.isNull("referenceSnapshot")) {
            null
        } else {
            decodeSnapshot(obj.getJSONObject("referenceSnapshot"), limits)
        }
        return BackupAnswer(
            answer = PracticeRecordedAnswer(
                id = obj.str("id", 128),
                sessionId = obj.str("sessionId", 128),
                questionId = obj.str("questionId", 128),
                position = obj.nonNegativeInt("position"),
                kind = obj.str("kind", 32),
                result = obj.str("result", 32),
                userAnswer = decodeUserAnswer(obj.getJSONObject("userAnswer"), limits),
                submittedAt = obj.long("submittedAt")
            ),
            referenceSnapshot = snapshot
        )
    }

    fun encodeDraft(draft: PracticeDraft): JSONObject = JSONObject()
        .put("selectedKeys", JSONArray(draft.selectedKeys))
        .put("blankAnswers", JSONArray(draft.blankAnswers))
        .put("text", draft.text)
        .put("referenceRevealed", draft.referenceRevealed)

    fun decodeDraft(obj: JSONObject, limits: BackupLimits): PracticeDraft = PracticeDraft(
        selectedKeys = obj.strings("selectedKeys", limits),
        blankAnswers = obj.strings("blankAnswers", limits),
        text = obj.str("text", limits.maxStringBytes),
        referenceRevealed = obj.bool("referenceRevealed")
    )

    fun encodeUserAnswer(answer: PracticeUserAnswer): JSONObject = JSONObject()
        .put("selectedKeys", JSONArray(answer.selectedKeys))
        .put("blankAnswers", JSONArray(answer.blankAnswers))
        .put("text", answer.text)

    fun decodeUserAnswer(obj: JSONObject, limits: BackupLimits): PracticeUserAnswer = PracticeUserAnswer(
        selectedKeys = obj.strings("selectedKeys", limits),
        blankAnswers = obj.strings("blankAnswers", limits),
        text = obj.str("text", limits.maxStringBytes)
    )

    fun isSafeEntryPath(path: String): Boolean {
        if (path.isBlank() || path.length > 300) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains('\\') || path.contains(':')) return false
        val parts = path.split('/')
        return parts.isNotEmpty() && parts.none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun validateEntrySet(metas: List<BackupLibraryMeta>, entries: List<BackupEntryRef>) {
        val expected = mutableSetOf<String>()
        for (meta in metas) {
            expected.add(questionsEntry(meta.ordinal))
            expected.add(sessionsEntry(meta.ordinal))
            expected.add(answersEntry(meta.ordinal))
            meta.pdf?.let { expected.add(it.entry) }
        }
        val actual = entries.map { it.path }
        if (actual.size != actual.toSet().size) fail(BackupError.DUPLICATE_ENTRY, "备份清单包含重复条目")
        if (actual.toSet() != expected) fail(BackupError.BAD_MANIFEST, "备份清单条目与题库不匹配")
    }

    private fun <T> nested(
        raw: String,
        decode: (String) -> T,
        encode: (T) -> String,
        field: String
    ): T {
        val value = decode(raw)
        if (encode(value) != raw) {
            fail(BackupError.BAD_JSON, "字段 $field 的嵌套 JSON 不是规范格式")
        }
        return value
    }

    private fun JSONObject.strings(name: String, limits: BackupLimits): List<String> {
        val array = array(name)
        return (0 until array.length()).map { index ->
            val value = array.get(index)
            if (value !is String) fail(BackupError.BAD_FIELD, "字段 $name 必须是字符串数组")
            checkText(value, name, limits.maxStringBytes)
            value
        }
    }

    private fun JSONObject.ints(name: String): List<Int> {
        val array = array(name)
        return (0 until array.length()).map { index ->
            val value = array.get(index)
            if (value !is Number) fail(BackupError.BAD_FIELD, "字段 $name 必须是整数数组")
            val double = value.toDouble()
            if (double % 1.0 != 0.0 || double < Int.MIN_VALUE || double > Int.MAX_VALUE) {
                fail(BackupError.BAD_FIELD, "字段 $name 包含非法整数")
            }
            double.toInt()
        }
    }

    private fun JSONObject.str(name: String, maxBytes: Int): String {
        if (!has(name)) fail(BackupError.BAD_FIELD, "缺少字段：$name")
        if (isNull(name)) fail(BackupError.BAD_FIELD, "字段 $name 不能为空")
        val value = get(name)
        if (value !is String) fail(BackupError.BAD_FIELD, "字段 $name 必须是字符串")
        checkText(value, name, maxBytes)
        return value
    }

    private fun JSONObject.strOrNull(name: String, maxBytes: Int): String? {
        if (!has(name)) fail(BackupError.BAD_FIELD, "缺少字段：$name")
        if (isNull(name)) return null
        val value = get(name)
        if (value !is String) fail(BackupError.BAD_FIELD, "字段 $name 必须是字符串或 null")
        checkText(value, name, maxBytes)
        return value
    }

    private fun JSONObject.int(name: String): Int {
        if (!has(name)) fail(BackupError.BAD_FIELD, "缺少字段：$name")
        val value = get(name)
        if (value !is Number) fail(BackupError.BAD_FIELD, "字段 $name 必须是整数")
        val double = value.toDouble()
        if (double % 1.0 != 0.0 || double < Int.MIN_VALUE || double > Int.MAX_VALUE) {
            fail(BackupError.BAD_FIELD, "字段 $name 必须是整数")
        }
        return double.toInt()
    }

    private fun JSONObject.nonNegativeInt(name: String): Int {
        val value = int(name)
        if (value < 0) fail(BackupError.BAD_FIELD, "字段 $name 不能为负")
        return value
    }

    private fun JSONObject.long(name: String): Long {
        if (!has(name)) fail(BackupError.BAD_FIELD, "缺少字段：$name")
        val value = get(name)
        if (value !is Number) fail(BackupError.BAD_FIELD, "字段 $name 必须是整数")
        val double = value.toDouble()
        if (double % 1.0 != 0.0) fail(BackupError.BAD_FIELD, "字段 $name 必须是整数")
        return double.toLong()
    }

    private fun JSONObject.intOrNull(name: String): Int? {
        if (!has(name)) fail(BackupError.BAD_FIELD, "缺少字段：$name")
        if (isNull(name)) return null
        return int(name)
    }

    private fun JSONObject.longOrNull(name: String): Long? {
        if (!has(name)) fail(BackupError.BAD_FIELD, "缺少字段：$name")
        if (isNull(name)) return null
        return long(name)
    }

    private fun JSONObject.bool(name: String): Boolean {
        if (!has(name)) fail(BackupError.BAD_FIELD, "缺少字段：$name")
        val value = get(name)
        if (value !is Boolean) fail(BackupError.BAD_FIELD, "字段 $name 必须是布尔值")
        return value
    }

    private fun JSONObject.array(name: String): JSONArray {
        if (!has(name)) fail(BackupError.BAD_FIELD, "缺少字段：$name")
        val value = get(name)
        if (value !is JSONArray) fail(BackupError.BAD_FIELD, "字段 $name 必须是数组")
        return value
    }

    private fun checkText(value: String, name: String, maxBytes: Int) {
        if (value.toByteArray(Charsets.UTF_8).size > maxBytes) {
            fail(BackupError.TOO_LARGE, "字段 $name 超过单字段上限")
        }
    }

    private fun fail(error: BackupError, message: String): Nothing = throw BackupException(error, message)

    private inline fun <T> guarded(block: () -> T): T {
        return try {
            block()
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(BackupError.BAD_JSON, "备份 JSON 结构无效：${e.message ?: "格式错误"}", e)
        }
    }
}
