package com.parent.tvlock.db.dao

import android.content.ContentValues
import android.database.Cursor
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.model.Question
import com.parent.tvlock.db.model.WrongQuestion
import com.parent.tvlock.util.L

/**
 * 错题本数据访问对象
 */
class WrongQuestionDao(private val dbHelper: AppDatabaseHelper) {

    /**
     * 记录错题：已存在则累加错误次数并刷新错误选项与时间；不存在则新增
     */
    fun recordWrongAnswer(
        questionId: Long,
        lastWrongAnswer: String,
        timestamp: Long = System.currentTimeMillis()
    ): Long {
        val db = dbHelper.writableDatabase
        var resultId: Long = -1

        val cursor = db.rawQuery(
            "SELECT id, wrongCount FROM ${AppDatabaseHelper.TABLE_WRONG_QUESTIONS} WHERE questionId = ? LIMIT 1",
            arrayOf(questionId.toString())
        )

        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            val currentCount = cursor.getInt(1)
            cursor.close()

            val values = ContentValues().apply {
                put("wrongCount", currentCount + 1)
                put("lastWrongAnswer", lastWrongAnswer)
                put("lastWrongTime", timestamp)
                put("isMastered", 0) // 再次做错，重新标记为未掌握
            }
            db.update(AppDatabaseHelper.TABLE_WRONG_QUESTIONS, values, "id = ?", arrayOf(id.toString()))
            resultId = id
        } else {
            cursor.close()
            val values = ContentValues().apply {
                put("questionId", questionId)
                put("wrongCount", 1)
                put("lastWrongAnswer", lastWrongAnswer)
                put("lastWrongTime", timestamp)
                put("isMastered", 0)
            }
            resultId = db.insert(AppDatabaseHelper.TABLE_WRONG_QUESTIONS, null, values)
        }

        return resultId
    }

    /**
     * 标记错题已掌握/消灭
     */
    fun markAsMastered(questionId: Long, timestamp: Long = System.currentTimeMillis()): Boolean {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("isMastered", 1)
            put("masteredTime", timestamp)
        }
        return db.update(
            AppDatabaseHelper.TABLE_WRONG_QUESTIONS,
            values,
            "questionId = ?",
            arrayOf(questionId.toString())
        ) > 0
    }

    /**
     * 随机获取一个未攻克的错题 questionId
     */
    fun getRandomUnmasteredQuestionId(): Long? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT questionId FROM ${AppDatabaseHelper.TABLE_WRONG_QUESTIONS} WHERE isMastered = 0 ORDER BY RANDOM() LIMIT 1",
            null
        )
        return if (cursor.moveToFirst()) {
            val qId = cursor.getLong(0)
            cursor.close()
            qId
        } else {
            cursor.close()
            null
        }
    }

    /**
     * 获取未攻克错题数量
     */
    fun getUnmasteredCount(): Long {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${AppDatabaseHelper.TABLE_WRONG_QUESTIONS} WHERE isMastered = 0",
            null
        )
        val count = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        cursor.close()
        return count
    }

    /**
     * 查询错题列表（支持连带查询题目详情，按最后做错时间倒序）
     */
    fun getAllWrongQuestions(onlyUnmastered: Boolean = false): List<WrongQuestion> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<WrongQuestion>()

        val whereClause = if (onlyUnmastered) "WHERE w.isMastered = 0" else ""
        val sql = """
            SELECT 
                w.id AS w_id, w.questionId AS w_questionId, w.wrongCount AS w_wrongCount,
                w.lastWrongAnswer AS w_lastWrongAnswer, w.lastWrongTime AS w_lastWrongTime,
                w.isMastered AS w_isMastered, w.masteredTime AS w_masteredTime,
                q.id AS q_id, q.subject AS q_subject, q.questionText AS q_questionText,
                q.questionImageUrl AS q_questionImageUrl, q.localImagePath AS q_localImagePath,
                q.optionA AS q_optionA, q.optionB AS q_optionB, q.optionC AS q_optionC, q.optionD AS q_optionD,
                q.optionAImageUrl AS q_optionAImageUrl, q.optionBImageUrl AS q_optionBImageUrl,
                q.optionCImageUrl AS q_optionCImageUrl, q.optionDImageUrl AS q_optionDImageUrl,
                q.correctAnswer AS q_correctAnswer, q.explanation AS q_explanation,
                q.isAnsweredCorrectly AS q_isAnsweredCorrectly
            FROM ${AppDatabaseHelper.TABLE_WRONG_QUESTIONS} w
            LEFT JOIN ${AppDatabaseHelper.TABLE_QUESTIONS} q ON w.questionId = q.id
            $whereClause
            ORDER BY w.lastWrongTime DESC
        """.trimIndent()

        val cursor = db.rawQuery(sql, null)
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
                    optionAImageUrl = cursor.getString(cursor.getColumnIndexOrThrow("q_optionAImageUrl")),
                    optionBImageUrl = cursor.getString(cursor.getColumnIndexOrThrow("q_optionBImageUrl")),
                    optionCImageUrl = cursor.getString(cursor.getColumnIndexOrThrow("q_optionCImageUrl")),
                    optionDImageUrl = cursor.getString(cursor.getColumnIndexOrThrow("q_optionDImageUrl")),
                    correctAnswer = cursor.getString(cursor.getColumnIndexOrThrow("q_correctAnswer")),
                    explanation = cursor.getString(cursor.getColumnIndexOrThrow("q_explanation")),
                    isAnsweredCorrectly = cursor.getInt(cursor.getColumnIndexOrThrow("q_isAnsweredCorrectly"))
                )
            } else null

            val wrongQuestion = WrongQuestion(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("w_id")),
                questionId = cursor.getLong(cursor.getColumnIndexOrThrow("w_questionId")),
                wrongCount = cursor.getInt(cursor.getColumnIndexOrThrow("w_wrongCount")),
                lastWrongAnswer = cursor.getString(cursor.getColumnIndexOrThrow("w_lastWrongAnswer")),
                lastWrongTime = cursor.getLong(cursor.getColumnIndexOrThrow("w_lastWrongTime")),
                isMastered = cursor.getInt(cursor.getColumnIndexOrThrow("w_isMastered")),
                masteredTime = if (cursor.isNull(cursor.getColumnIndexOrThrow("w_masteredTime"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("w_masteredTime")),
                question = question
            )
            list.add(wrongQuestion)
        }
        cursor.close()
        return list
    }
}
