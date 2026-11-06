package com.parent.tvlock.db.model

import java.io.Serializable

/**
 * 错题本数据实体
 */
data class WrongQuestion(
    val id: Long = 0,
    val questionId: Long,                // 关联 questions 表的 id
    val wrongCount: Int = 1,             // 累计做错次数
    val lastWrongAnswer: String,         // 最近一次做错选择的答案（A/B/C/D）
    val lastWrongTime: Long,             // 最近一次答错时间戳
    val isMastered: Int = 0,             // 是否已攻克/掌握（0: 未攻克, 1: 已攻克）
    val masteredTime: Long? = null,      // 攻克时间戳
    // 可选关联的题目详情（便于UI展示）
    val question: Question? = null
) : Serializable
