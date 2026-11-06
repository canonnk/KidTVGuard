package com.parent.tvlock.db.dao

import android.content.ContentValues
import android.database.Cursor
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.model.Question
import com.parent.tvlock.util.L

/**
 * 题目数据访问对象
 */
class QuestionDao(private val dbHelper: AppDatabaseHelper) {

    /**
     * 插入单道题目
     */
    fun insert(question: Question): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("subject", question.subject)
            put("questionText", question.questionText)
            put("questionImageUrl", question.questionImageUrl)
            put("localImagePath", question.localImagePath)
            put("optionA", question.optionA)
            put("optionB", question.optionB)
            put("optionC", question.optionC)
            put("optionD", question.optionD)
            put("optionAImageUrl", question.optionAImageUrl)
            put("optionBImageUrl", question.optionBImageUrl)
            put("optionCImageUrl", question.optionCImageUrl)
            put("optionDImageUrl", question.optionDImageUrl)
            put("correctAnswer", question.correctAnswer)
            put("explanation", question.explanation)
            put("isAnsweredCorrectly", question.isAnsweredCorrectly)
        }
        return db.insert(AppDatabaseHelper.TABLE_QUESTIONS, null, values)
    }

    /**
     * 批量导入题目（事务优化）
     */
    fun batchInsert(questions: List<Question>): Int {
        val db = dbHelper.writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (q in questions) {
                val values = ContentValues().apply {
                    put("subject", q.subject)
                    put("questionText", q.questionText)
                    put("questionImageUrl", q.questionImageUrl)
                    put("localImagePath", q.localImagePath)
                    put("optionA", q.optionA)
                    put("optionB", q.optionB)
                    put("optionC", q.optionC)
                    put("optionD", q.optionD)
                    put("optionAImageUrl", q.optionAImageUrl)
                    put("optionBImageUrl", q.optionBImageUrl)
                    put("optionCImageUrl", q.optionCImageUrl)
                    put("optionDImageUrl", q.optionDImageUrl)
                    put("correctAnswer", q.correctAnswer)
                    put("explanation", q.explanation)
                    put("isAnsweredCorrectly", q.isAnsweredCorrectly)
                }
                val rowId = db.insert(AppDatabaseHelper.TABLE_QUESTIONS, null, values)
                if (rowId != -1L) count++
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            L.e("Error batch inserting questions", tr = e)
        } finally {
            db.endTransaction()
        }
        return count
    }

    /**
     * 批量导入或更新题目（以题目文本和科目为唯一性校验）
     */
    fun batchInsertOrUpdate(questions: List<Question>): Int {
        val db = dbHelper.writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (q in questions) {
                val values = ContentValues().apply {
                    put("subject", q.subject)
                    put("questionText", q.questionText)
                    put("questionImageUrl", q.questionImageUrl)
                    if (q.localImagePath != null) {
                        put("localImagePath", q.localImagePath)
                    }
                    put("optionA", q.optionA)
                    put("optionB", q.optionB)
                    put("optionC", q.optionC)
                    put("optionD", q.optionD)
                    put("optionAImageUrl", q.optionAImageUrl)
                    put("optionBImageUrl", q.optionBImageUrl)
                    put("optionCImageUrl", q.optionCImageUrl)
                    put("optionDImageUrl", q.optionDImageUrl)
                    put("correctAnswer", q.correctAnswer)
                    put("explanation", q.explanation)
                }

                // 检查是否已有相同科目和题干的题目
                val cursor = db.rawQuery(
                    "SELECT id, localImagePath, isAnsweredCorrectly FROM ${AppDatabaseHelper.TABLE_QUESTIONS} WHERE subject = ? AND questionText = ? LIMIT 1",
                    arrayOf(q.subject, q.questionText)
                )

                if (cursor.moveToFirst()) {
                    val existingId = cursor.getLong(0)
                    val existingLocalImage = cursor.getString(1)
                    if (q.localImagePath == null && existingLocalImage != null) {
                        values.put("localImagePath", existingLocalImage)
                    }
                    db.update(AppDatabaseHelper.TABLE_QUESTIONS, values, "id = ?", arrayOf(existingId.toString()))
                } else {
                    values.put("isAnsweredCorrectly", q.isAnsweredCorrectly)
                    db.insert(AppDatabaseHelper.TABLE_QUESTIONS, null, values)
                }
                cursor.close()
                count++
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            L.e("Error batch insert/update questions", tr = e)
        } finally {
            db.endTransaction()
        }
        return count
    }

    /**
     * 标记某题已正确解答
     */
    fun markAnsweredCorrectly(questionId: Long): Boolean {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("isAnsweredCorrectly", 1)
        }
        return db.update(AppDatabaseHelper.TABLE_QUESTIONS, values, "id = ?", arrayOf(questionId.toString())) > 0
    }

    /**
     * 更新题目的本地图片路径
     */
    fun updateLocalImagePath(questionId: Long, localPath: String): Boolean {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("localImagePath", localPath)
        }
        return db.update(AppDatabaseHelper.TABLE_QUESTIONS, values, "id = ?", arrayOf(questionId.toString())) > 0
    }

    /**
     * 随机抽取一道未答对的题目；如果全部答对过，则从全题库随机抽取
     */
    fun getRandomQuestionForLock(): Question? {
        val db = dbHelper.readableDatabase
        // 1. 优先抽取未答对的题
        var cursor = db.rawQuery(
            "SELECT * FROM ${AppDatabaseHelper.TABLE_QUESTIONS} WHERE isAnsweredCorrectly = 0 ORDER BY RANDOM() LIMIT 1",
            null
        )
        if (cursor.moveToFirst()) {
            val q = cursor.toQuestion()
            cursor.close()
            return q
        }
        cursor.close()

        // 2. 若所有题目都答对过，则从全部题库中随机抽取一道
        cursor = db.rawQuery(
            "SELECT * FROM ${AppDatabaseHelper.TABLE_QUESTIONS} ORDER BY RANDOM() LIMIT 1",
            null
        )
        if (cursor.moveToFirst()) {
            val q = cursor.toQuestion()
            cursor.close()
            return q
        }
        cursor.close()
        return null
    }

    /**
     * 根据 ID 获取题目
     */
    fun getQuestionById(id: Long): Question? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM ${AppDatabaseHelper.TABLE_QUESTIONS} WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        )
        return if (cursor.moveToFirst()) {
            val q = cursor.toQuestion()
            cursor.close()
            q
        } else {
            cursor.close()
            null
        }
    }

    /**
     * 获取题目总数
     */
    fun getCount(): Long {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM ${AppDatabaseHelper.TABLE_QUESTIONS}", null)
        val count = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        cursor.close()
        return count
    }

    /**
     * 获取所有题目列表
     */
    fun getAllQuestions(): List<Question> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<Question>()
        val cursor = db.rawQuery("SELECT * FROM ${AppDatabaseHelper.TABLE_QUESTIONS} ORDER BY id ASC", null)
        while (cursor.moveToNext()) {
            list.add(cursor.toQuestion())
        }
        cursor.close()
        return list
    }

    /**
     * 重置所有题目的答对状态
     */
    fun resetAllAnswerStatus(): Boolean {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("isAnsweredCorrectly", 0)
        }
        return db.update(AppDatabaseHelper.TABLE_QUESTIONS, values, null, null) > 0
    }

    private fun Cursor.toQuestion(): Question {
        val optAImgIdx = getColumnIndex("optionAImageUrl")
        val optBImgIdx = getColumnIndex("optionBImageUrl")
        val optCImgIdx = getColumnIndex("optionCImageUrl")
        val optDImgIdx = getColumnIndex("optionDImageUrl")

        return Question(
            id = getLong(getColumnIndexOrThrow("id")),
            subject = getString(getColumnIndexOrThrow("subject")),
            questionText = getString(getColumnIndexOrThrow("questionText")),
            questionImageUrl = getString(getColumnIndexOrThrow("questionImageUrl")),
            localImagePath = getString(getColumnIndexOrThrow("localImagePath")),
            optionA = getString(getColumnIndexOrThrow("optionA")),
            optionB = getString(getColumnIndexOrThrow("optionB")),
            optionC = getString(getColumnIndexOrThrow("optionC")),
            optionD = getString(getColumnIndexOrThrow("optionD")),
            optionAImageUrl = if (optAImgIdx >= 0) getString(optAImgIdx) else null,
            optionBImageUrl = if (optBImgIdx >= 0) getString(optBImgIdx) else null,
            optionCImageUrl = if (optCImgIdx >= 0) getString(optCImgIdx) else null,
            optionDImageUrl = if (optDImgIdx >= 0) getString(optDImgIdx) else null,
            correctAnswer = getString(getColumnIndexOrThrow("correctAnswer")),
            explanation = getString(getColumnIndexOrThrow("explanation")),
            isAnsweredCorrectly = getInt(getColumnIndexOrThrow("isAnsweredCorrectly"))
        )
    }
}
