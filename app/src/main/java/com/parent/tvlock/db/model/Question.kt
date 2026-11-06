package com.parent.tvlock.db.model

import java.io.Serializable

/**
 * 题目数据实体
 */
data class Question(
    val id: Long = 0,
    val subject: String,                 // 科目（语文、数学、英语、物理、幼儿启蒙等）
    val questionText: String,            // 题干内容
    val questionImageUrl: String? = null,// 题目网络图片URL（可选）
    val localImagePath: String? = null,  // 题目本地缓存图片绝对路径（保留向下兼容）
    val optionA: String,                 // 选项A文本
    val optionB: String,                 // 选项B文本
    val optionC: String,                 // 选项C文本
    val optionD: String,                 // 选项D文本
    val optionAImageUrl: String? = null, // 选项A图片URL（可选，方案2）
    val optionBImageUrl: String? = null, // 选项B图片URL（可选，方案2）
    val optionCImageUrl: String? = null, // 选项C图片URL（可选，方案2）
    val optionDImageUrl: String? = null, // 选项D图片URL（可选，方案2）
    val correctAnswer: String,           // 正确答案（A/B/C/D）
    val explanation: String? = null,     // 题目详细解析
    val isAnsweredCorrectly: Int = 0     // 是否已答对过（0: 未答对过, 1: 已答对）
) : Serializable
