package com.parent.tvlock.db.model

import java.io.Serializable

/**
 * 答题流水记录实体
 */
data class AnswerRecord(
    val id: Long = 0,
    val questionId: Long,                // 关联 questions 表的 id
    val selectedAnswer: String,          // 本次作答选择（A/B/C/D）
    val isCorrect: Int,                  // 本次是否正确（0: 错误, 1: 正确）
    val answerTime: Long,                // 答题提交时间戳
    val timeSpentSeconds: Int = 0,       // 本次答题用时秒数
    // 可选关联的题目详情（便于UI展示）
    val question: Question? = null
) : Serializable
