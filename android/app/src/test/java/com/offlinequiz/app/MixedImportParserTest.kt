package com.offlinequiz.app

import com.offlinequiz.app.data.QuestionMapper
import com.offlinequiz.app.model.QuestionValidator
import org.junit.Assert.*
import org.junit.Test
import pdfprobe.Model
import pdfprobe.QuestionParser

class MixedImportParserTest {
    private fun parse(text: String) = TextImportParser.parse(text).result

    @Test fun mixedQuestionTagsNeedNoGlobalType() {
        val result = parse("1.[单选题]哪项为偶数？\nA.2 B.3\n正确答案：A\n" +
            "2.[多选题]哪些是偶数？\nA.2 B.3 C.4\n我的答案：B\n正确答案：AC\n" +
            "3.[填空题]一周有()天。\n我的答案：\n(1)\n正确答案:\n(1)7\n" +
            "4.[判断题]一周有七天。\n我的答案：错\n正确答案：对\n" +
            "5.[简答题]举一种节水方法。\n正确答案：随手关水龙头。")
        assertEquals(listOf("single_choice", "multiple_choice", "fill_blank", "true_false", "short_answer"), result.questions.map { it.type })
        assertEquals(listOf(listOf("A"), listOf("A", "C"), listOf("7"), listOf("√"), listOf("随手关水龙头。")), result.questions.map { it.answers })
        assertTrue(result.questions.none { it.needsReview })
        var id = 0
        assertTrue(QuestionMapper.fromParseResult(result, "mixed") { "id-${id++}" }.all { QuestionValidator.validate(it).isEmpty() })
    }

    @Test fun mixedUntaggedQuestionsUseStructureAutomatically() {
        val result = parse("1.哪项为偶数？\nA.2 B.3\n答案：A\n" +
            "2.哪些是偶数？\nA.2 B.3 C.4\n答案：AC\n" +
            "3.一周有()天。\n我的答案：\n(1)\n正确答案:\n(1)7\n" +
            "4.一周有七天。\n正确答案：对\n" +
            "5.举一种节水方法。\n答案：关水龙头。")
        assertEquals(listOf("single_choice", "multiple_choice", "fill_blank", "true_false", "short_answer"), result.questions.map { it.type })
        assertTrue(result.questions.none { it.needsReview })
    }

    @Test fun tagVariantsAreRemovedFromStemAndOverrideAdvancedDefault() {
        for (tag in listOf("[多选题]", "【多选题】", "［多选题］")) {
            val q = TextImportParser.parse("1.$tag 哪些是偶数？\nA.2 B.3 C.4\n答案：AC", defaultType = "single_choice").result.questions.single()
            assertEquals("multiple_choice", q.type)
            assertEquals("哪些是偶数？", q.stem)
        }
    }

    @Test fun personalAnswerIsNeverReferenceOrPartOfOption() {
        val q = parse("1.[单选题]哪项为偶数？\nA.2\nB.3\n我的答案：B\n正确答案：A").questions.single()
        assertEquals(listOf("A"), q.answers)
        assertEquals("3", q.options.last().text)
        assertFalse(q.stem.contains("我的答案"))
        assertTrue(q.originalSentence.contains("我的答案：B"))
        assertTrue(parse("1.[判断题]题目。\n我的答案：对").questions.single().needsReview)
        assertTrue(parse("1.[判断题]题目。\n我的答案：对").questions.single().answers.isEmpty())
    }

    @Test fun emptyAndDoubleColonAnswersCanSpanLines() {
        val q = parse("1.[单选题]题目。\nA.甲 B.乙\n正确答案：：\nB\n查看解析").questions.single()
        assertEquals(listOf("B"), q.answers)
        assertFalse(q.needsReview)
    }

    @Test fun numberedFillAnswersStayWithQuestionAndRespectOrder() {
        val result = parse("1.[填空题]一周有()天，一天有（）小时。\n我的答案：\n(1)8\n(2)25\n正确答案：\n(1)7\n(2)24\n查看解析\n2.[判断题]题目。\n正确答案：错")
        assertEquals(2, result.questions.size)
        assertEquals(listOf("7", "24"), result.questions[0].answers)
        assertEquals("一周有____天，一天有____小时。", result.questions[0].stem)
        assertFalse(result.questions[0].needsReview)
        assertTrue(parse("1.[填空题]()和()\n答案：\n(1)甲\n(3)乙").questions.single().reviewReasons.contains("numbered_blank_answer_order"))
    }

    @Test fun explicitFillIsUsedInsteadOfUnderlineOnAnswerLabel() {
        val geometry = geometry("1.[填空题]一周有()天。\n我的答案：\n(1)\n正确答案：\n(1)7", 1)
        geometry.pages.add(Model.PageInfo().apply { number = 1; height = 840f })
        geometry.underlines.add(Model.Underline().apply { matched = true; lineGlobal = 3; cleanText = "正确答案" })
        val q = QuestionParser.parse(geometry).questions.single()
        assertEquals(listOf("7"), q.answers)
        assertFalse(q.needsReview)
    }

    @Test fun unlabeledLettersAndBackslashAnswersArePreservedForReview() {
        val q = parse("1.[多选题]题目。\nA.甲 B.乙 C.丙\nBC\n正确答案：AC").questions.single()
        assertEquals(listOf("A", "C"), q.answers)
        assertEquals("丙", q.options.last().text)
        assertTrue(q.reviewReasons.contains("unlabeled_answer_text"))
        assertTrue(q.originalSentence.contains("BC"))
        val fill = parse("1.[填空题]()\n正确答案：\n(1)甲\\乙").questions.single()
        assertEquals(listOf("甲\\乙"), fill.answers)
        assertFalse(fill.needsReview)
    }

    @Test fun quotedTypeWordDoesNotOverrideQuestionType() {
        val q = parse("1.请解释“[多选题]”这个术语。\n答案：可以选择多个选项的题目。").questions.single()
        assertEquals("short_answer", q.type)
    }

    @Test fun missingEvidenceIsOnePendingQuestionRatherThanBlockingWholeLibrary() {
        val result = parse("1.题目内容，缺少答案。\n2.[判断题]题目。\n正确答案：对")
        assertEquals(2, result.questions.size)
        assertEquals("unknown", result.questions[0].type)
        assertTrue(result.questions[0].needsReview)
        assertFalse(result.questions[1].needsReview)
    }

    @Test fun autoChoiceAnswerTableCanResolveMultipleChoice() {
        val q = parse("1.哪些是偶数？\nA.2 B.3 C.4\n参考答案\n1. AC").questions.single()
        assertEquals("multiple_choice", q.type)
        assertEquals(listOf("A", "C"), q.answers)
        assertFalse(q.needsReview)
    }

    @Test fun autoShortAnswerCanStartWithNumberedPointsOnNextLine() {
        val result = parse("1.请写出两种方法。\n答案：\n(1)方法甲\n(2)方法乙\n2.[判断题]题目。\n正确答案：对")
        assertEquals(2, result.questions.size)
        assertEquals("short_answer", result.questions.first().type)
        assertTrue(result.questions.first().referenceAnswer.contains("方法乙"))
    }

    @Test fun repeatedMarginsAreRemovedButBodyAndStructuralLinesRemain() {
        val geometry = Model.Geometry()
        for (page in 1..4) {
            geometry.pages.add(Model.PageInfo().apply { number = page; height = 840f })
            fun line(text: String, y: Float) { geometry.lines.add(Model.Line().apply {
                this.page = page; lineNo = geometry.lines.size + 1; globalIndex = geometry.lines.size; this.text = text; baseline = y
            }) }
            line("$page.[单选题]重复正文", 100f)
            line("A.相同内容", 400f)
            line("B.相同内容", 800f)
            line("正确答案：A", 815f)
            line("自制页脚标记", 835f)
        }
        val result = QuestionParser.parse(geometry)
        assertEquals(4, result.questions.size)
        assertEquals(4, geometry.footerLines)
        assertTrue(result.questions.all { it.options.last().text == "相同内容" && !it.needsReview })
        assertTrue(result.questions.none { it.originalSentence.contains("自制页脚标记") })
        assertEquals(4, QuestionParser.parse(geometry).questions.size)
    }

    @Test fun singleOccurrenceMarginTextIsNotRemoved() {
        val g = geometry("1.[单选题]题目\nA.甲\nB.乙\n唯一续行\n答案：A", 1)
        for (page in 1..3) g.pages.add(Model.PageInfo().apply { number = page; height = 840f })
        g.lines[3].baseline = 830f
        val q = QuestionParser.parse(g).questions.single()
        assertTrue(q.options.last().text.contains("唯一续行"))
    }

    private fun geometry(text: String, page: Int) = Model.Geometry().apply {
        text.lines().forEachIndexed { index, value -> lines.add(Model.Line().apply {
            this.page = page; lineNo = index + 1; globalIndex = index; this.text = value; baseline = 100f + index * 20f
        }) }
    }
}
