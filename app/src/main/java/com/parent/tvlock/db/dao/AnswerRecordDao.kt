package com.parent.tvlock.db.dao

import android.content.ContentValues
import android.database.Cursor
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.model.AnswerRecord
import com.parent.tvlock.db.model.Question
import java.util.Calendar

/**
 * 答题流水记录数据访问对象
 */
class AnswerRecordDao(private val dbHelper: AppDatabaseHelper) {

    /**
     * 插入一条答题记录
     */
    fun insert(record: AnswerRecord): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("questionId", record.questionId)
            put("selectedAnswer", record.selectedAnswer)
            put("isCorrect", record.isCorrect)
            put("answerTime", record.answerTime)
            put("timeSpentSeconds", record.timeSpentSeconds)
        }
        return db.insert(AppDatabaseHelper.TABLE_ANSWER_RECORDS, null, values)
    }

    /**
     * 查询最近答题记录（按答题时间倒序，关联题目详情）
     */
    fun getRecentRecords(limit: Int = 100): List<AnswerRecord> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AnswerRecord>()

        val sql = """
            SELECT 
                r.id AS r_id, r.questionId AS r_questionId, r.selectedAnswer AS r_selectedAnswer,
                r.isCorrect AS r_isCorrect, r.answerTime AS r_answerTime, r.timeSpentSeconds AS r_timeSpentSeconds,
                q.id AS q_id, q.subject AS q_subject, q.questionText AS q_questionText,
                q.questionImageUrl AS q_questionImageUrl, q.localImagePath AS q_localImagePath,
                q.optionA AS q_optionA, q.optionB AS q_optionB, q.optionC AS q_optionC, q.optionD AS q_optionD,
                q.correctAnswer AS q_correctAnswer, q.explanation AS q_explanation,
                q.isAnsweredCorrectly AS q_isAnsweredCorrectly
            FROM ${AppDatabaseHelper.TABLE_ANSWER_RECORDS} r
            LEFT JOIN ${AppDatabaseHelper.TABLE_QUESTIONS} q ON r.questionId = q.id
            ORDER BY r.answerTime DESC
            LIMIT ?
        """.trimIndent()

        val cursor = db.rawQuery(sql, arrayOf(limit.toString()))
        while (cursor.moveToNext()) {
            val question = if (!cursor.isNull(cursor.getColumnIndexOrThrow("q_id"))) {
                Question(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("q_id")),
                    subject = cursor.getString(cursor.getColumnIndexOrThrow("q_subject")),
                    questionText = cursor.getString(cursor.getColumnIndexOrThrow("q_questionText")),
                    questionImageUrl = cursor.getString(cursor.getColumnIndexOrThrow("q_questionImageUrl")),
                    localImagePath = cursor.getString(cursor.getColumnIndexOrThrow("q_localImagePath")),
                    optionA = cursor.getString(cursor.getColumnIndexOrThrow("q_optionA")),
                    optionB = cursor.getString(cursor.getColumnIndexOrThrow("q_optionB")),
                    optionC = cursor.getString(cursor.getColumnIndexOrThrow("q_optionC")),
                    optionD = cursor.getString(cursor.getColumnIndexOrThrow("q_optionD")),
                    correctAnswer = cursor.getString(cursor.getColumnIndexOrThrow("q_correctAnswer")),
                    explanation = cursor.getString(cursor.getColumnIndexOrThrow("q_explanation")),
                    isAnsweredCorrectly = cursor.getInt(cursor.getColumnIndexOrThrow("q_isAnsweredCorrectly"))
                )
            } else null

            val record = AnswerRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("r_id")),
                questionId = cursor.getLong(cursor.getColumnIndexOrThrow("r_questionId")),
                selectedAnswer = cursor.getString(cursor.getColumnIndexOrThrow("r_selectedAnswer")),
                isCorrect = cursor.getInt(cursor.getColumnIndexOrThrow("r_isCorrect")),
                answerTime = cursor.getLong(cursor.getColumnIndexOrThrow("r_answerTime")),
                timeSpentSeconds = cursor.getInt(cursor.getColumnIndexOrThrow("r_timeSpentSeconds")),
                question = question
            )
            list.add(record)
        }
        cursor.close()
        return list
    }

    /**
     * 获取今日答题统计：Pair(今日答题总数, 今日答对数)
     */
    fun getTodayStats(): Pair<Int, Int> {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis

        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT 
                COUNT(*) AS totalCount,
                SUM(CASE WHEN isCorrect = 1 THEN 1 ELSE 0 END) AS correctCount
            FROM ${AppDatabaseHelper.TABLE_ANSWER_RECORDS}
            WHERE answerTime >= ?
            """.trimIndent(),
            arrayOf(startOfDay.toString())
        )

        var total = 0
        var correct = 0
        if (cursor.moveToFirst()) {
            total = cursor.getInt(0)
            correct = if (cursor.isNull(1)) 0 else cursor.getInt(1)
        }
        cursor.close()
        return Pair(total, correct)
    }

    /**
     * 获取历史作答总数
     */
    fun getTotalCount(): Long {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM ${AppDatabaseHelper.TABLE_ANSWER_RECORDS}", null)
        val count = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        cursor.close()
        return count
    }
}
