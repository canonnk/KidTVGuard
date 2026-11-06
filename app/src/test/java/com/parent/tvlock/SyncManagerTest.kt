package com.parent.tvlock

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.parent.tvlock.db.model.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SyncManagerTest {

    @Test
    fun testDefaultQuestionsJsonFormat() {
        val jsonFile = File("src/main/assets/default_questions.json")
        assertTrue("default_questions.json should exist", jsonFile.exists())

        val jsonContent = jsonFile.readText(Charsets.UTF_8)
        val gson = Gson()
        val type = object : TypeToken<List<Question>>() {}.type
        val questions: List<Question> = gson.fromJson(jsonContent, type)

        assertTrue("Should have at least 5 default questions", questions.size >= 5)
        for (q in questions) {
            assertNotNull(q.subject)
            assertTrue("Subject should not be empty", q.subject.isNotBlank())
            assertTrue("QuestionText should not be empty", q.questionText.isNotBlank())
            assertTrue("OptionA should not be empty", q.optionA.isNotBlank())
            assertTrue("OptionB should not be empty", q.optionB.isNotBlank())
            assertTrue("OptionC should not be empty", q.optionC.isNotBlank())
            assertTrue("OptionD should not be empty", q.optionD.isNotBlank())
            assertTrue("CorrectAnswer should be A, B, C, or D", q.correctAnswer in listOf("A", "B", "C", "D"))
            assertNotNull("Explanation should exist", q.explanation)
        }
    }

    @Test
    fun testSchemeTwoKindergartenQuestionsParsed() {
        val jsonFile = File("src/main/assets/default_questions.json")
        val jsonContent = jsonFile.readText(Charsets.UTF_8)
        val questions: List<Question> = Gson().fromJson(jsonContent, object : TypeToken<List<Question>>() {}.type)

        val schemeTwoQ = questions.find { it.optionAImageUrl != null }
        assertNotNull("Should contain at least one question with option images (Scheme 2)", schemeTwoQ)
        assertEquals("幼儿启蒙", schemeTwoQ?.subject)
        assertNotNull(schemeTwoQ?.optionAImageUrl)
        assertNotNull(schemeTwoQ?.optionBImageUrl)
        assertNotNull(schemeTwoQ?.optionCImageUrl)
        assertNotNull(schemeTwoQ?.optionDImageUrl)
        assertEquals("A", schemeTwoQ?.correctAnswer)
    }

    @Test
    fun testStemImageCountingQuestionParsed() {
        val jsonFile = File("src/main/assets/default_questions.json")
        val jsonContent = jsonFile.readText(Charsets.UTF_8)
        val questions: List<Question> = Gson().fromJson(jsonContent, object : TypeToken<List<Question>>() {}.type)

        val countingQ = questions.find { it.questionText.contains("【数一数】") }
        assertNotNull("Should contain counting question", countingQ)
        assertNotNull(countingQ?.questionImageUrl)
        assertEquals("幼儿启蒙", countingQ?.subject)
    }
}
