package com.parent.tvlock

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.parent.tvlock.db.model.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            if (!q.isVoiceType) {
                assertTrue("OptionA should not be empty", q.optionA.isNotBlank())
                assertTrue("OptionB should not be empty", q.optionB.isNotBlank())
                assertTrue("OptionC should not be empty", q.optionC.isNotBlank())
                assertTrue("OptionD should not be empty", q.optionD.isNotBlank())
                assertTrue("CorrectAnswer should be A, B, C, or D", q.correctAnswer in listOf("A", "B", "C", "D"))
            } else {
                assertTrue("Voice question answer should not be empty", q.correctAnswer.isNotBlank())
            }
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

    @Test
    fun testVoiceQuestionsParsed() {
        val jsonFile = File("src/main/assets/default_questions.json")
        val jsonContent = jsonFile.readText(Charsets.UTF_8)
        val questions: List<Question> = Gson().fromJson(jsonContent, object : TypeToken<List<Question>>() {}.type)

        val voiceQuestions = questions.filter { it.isVoiceType }
        assertTrue("Should contain at least 2 voice questions", voiceQuestions.size >= 2)
        val appleQ = voiceQuestions.find { it.correctAnswer == "苹果" }
        assertNotNull("Should have apple voice question", appleQ)
        assertEquals(Question.TYPE_VOICE, appleQ?.questionType)
        assertNotNull(appleQ?.questionImageUrl)
        assertNotNull(appleQ?.acceptableAnswers)
    }

    @Test
    fun testVoiceAnswerMatcherLogic() {
        val matcher = com.parent.tvlock.util.VoiceAnswerMatcher

        // 1. 幼儿带修饰词与前缀
        val res1 = matcher.match("这是一个大苹果呀。", "苹果", "红苹果,大苹果,apple")
        assertTrue("Should match with prefix and tone suffix", res1.isMatched)
        assertEquals("大苹果", res1.cleanedText)

        // 2. 纯词匹配
        val res2 = matcher.match("苹果。", "苹果", null)
        assertTrue("Should match exact clean text", res2.isMatched)

        // 3. 同音与数字
        val res3 = matcher.match("一共有三只", "3", "3只,三只")
        assertTrue("Should match number equivalence", res3.isMatched)

        // 4. 错误回答
        val res4 = matcher.match("大香蕉", "苹果", "红苹果,大苹果")
        assertFalse("Should fail for mismatched word", res4.isMatched)
    }
}
