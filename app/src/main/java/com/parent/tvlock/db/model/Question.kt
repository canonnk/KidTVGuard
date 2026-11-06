package com.parent.tvlock.db.model

import java.io.Serializable

/**
 * 题目数据实体
 */
data class Question(
    val id: Long = 0,
    val subject: String,                 // 科目（语文、数学、英语、物理、幼儿启蒙等）
    val questionType: String = TYPE_CHOICE, // 题目类型: "CHOICE" (4选项单选), "VOICE" (中央大方图语音认读)
    val questionText: String,            // 题干内容
    val questionImageUrl: String? = null,// 题目网络图片URL（语音题作为中央大正方形图案使用）
    val localImagePath: String? = null,  // 题目本地缓存图片绝对路径（向下兼容）
    val optionA: String = "",            // 选项A文本（语音题可留空）
    val optionB: String = "",            // 选项B文本
    val optionC: String = "",            // 选项C文本
    val optionD: String = "",            // 选项D文本
    val optionAImageUrl: String? = null, // 选项A图片URL（方案2）
    val optionBImageUrl: String? = null, // 选项B图片URL（方案2）
    val optionCImageUrl: String? = null, // 选项C图片URL（方案2）
    val optionDImageUrl: String? = null, // 选项D图片URL（方案2）
    val correctAnswer: String,           // 正确答案（选择题为A/B/C/D；语音题为标准词，如"苹果"）
    val acceptableAnswers: String? = null,// 语音题允许的同义词/备选项（以英文逗号分隔，如"大苹果,红苹果,apple"）
    val explanation: String? = null,     // 题目详细解析
    val isAnsweredCorrectly: Int = 0     // 是否已答对过（0: 未答对过, 1: 已答对）
) : Serializable {
    companion object {
        const val TYPE_CHOICE = "CHOICE"
        const val TYPE_VOICE = "VOICE"
    }

    val isVoiceType: Boolean
        get() = questionType.equals(TYPE_VOICE, ignoreCase = true)
}
