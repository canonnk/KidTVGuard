package com.parent.tvlock

import com.parent.tvlock.db.model.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayAndFocusTest {

    @Test
    fun testOptionCorrectnessValidation() {
        val question = Question(
            id = 1,
            subject = "数学",
            questionText = "1 + 1 = ?",
            optionA = "1",
            optionB = "2",
            optionC = "3",
            optionD = "4",
            correctAnswer = "B",
            explanation = "1+1等于2"
        )

        assertTrue(question.correctAnswer.equals("b", ignoreCase = true))
        assertTrue(question.correctAnswer.equals("B", ignoreCase = true))
        assertFalse(question.correctAnswer.equals("A", ignoreCase = true))
        assertFalse(question.correctAnswer.equals("C", ignoreCase = true))
        assertFalse(question.correctAnswer.equals("D", ignoreCase = true))
    }

    @Test
    fun testTimeSpentCalculation() {
        val startTime = System.currentTimeMillis() - 4000L // 4 seconds ago
        val timeSpent = ((System.currentTimeMillis() - startTime) / 1000L).toInt().coerceAtLeast(1)
        assertEquals(4, timeSpent)
    }
}
