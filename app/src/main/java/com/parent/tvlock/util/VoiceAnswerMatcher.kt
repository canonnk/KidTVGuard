package com.parent.tvlock.util

/**
 * 幼儿口语发音智能容错与语义比对器
 *
 * 专门针对 3~6 岁幼儿说话习惯优化：
 * 1. 过滤百度 ASR 自动附带的句末标点符号；
 * 2. 剥离幼儿高频口头习惯前缀（如“这是一个”、“那是”、“我看到是”、“有”等）；
 * 3. 剥离幼儿句尾语气助词（如“呀”、“啊”、“呢”、“吧”、“啦”、“哦”等）；
 * 4. 支持主答案与同义词变体列表（`acceptableAnswers`）双向包含匹配；
 * 5. 数字与汉字量词互通兼容（如“3”与“三”、“3只”与“三只”）。
 */
object VoiceAnswerMatcher {

    data class MatchResult(
        val isMatched: Boolean,
        val rawRecognized: String,
        val cleanedText: String,
        val matchedTarget: String? = null
    )

    private val PREFIXES_TO_STRIP = listOf(
        "这是一个", "那是一个", "这是一只", "那是一只",
        "这是一把", "那是一把", "这是一朵", "那是一朵",
        "这是一头", "那是一头", "这是一条", "那是一条",
        "我看到是", "我觉得是", "这应该是", "那应该是",
        "这是", "那是", "它是", "就是", "是一只", "是一个", "有一只", "有一个"
    )

    private val TONE_SUFFIXES = setOf('呀', '啊', '呢', '吧', '啦', '哦', '呗', '的', '嘛')

    /**
     * 比对识别结果与目标答案
     * @param rawText 百度 ASR 识别出的原始文本（如："这是一个红苹果呀。"）
     * @param correctAnswer 题目标准答案（如："苹果"）
     * @param acceptableAnswers 题库配置的变体/同义词列表（以中英文逗号隔开，如："大苹果,红苹果,一个小苹果,apple"）
     */
    fun match(rawText: String?, correctAnswer: String, acceptableAnswers: String? = null): MatchResult {
        if (rawText.isNullOrBlank()) {
            return MatchResult(false, rawText ?: "", "")
        }

        val cleaned = cleanRecognizedText(rawText)

        // 构建全部目标比对词集合
        val targets = mutableListOf<String>()
        val mainAns = cleanPunctuationAndSpace(correctAnswer)
        if (mainAns.isNotBlank()) targets.add(mainAns)

        if (!acceptableAnswers.isNullOrBlank()) {
            val parts = acceptableAnswers.split(",", "，", "、", ";", "；")
            for (p in parts) {
                val cleanedPart = cleanPunctuationAndSpace(p)
                if (cleanedPart.isNotBlank() && !targets.contains(cleanedPart)) {
                    targets.add(cleanedPart)
                }
            }
        }

        // 梯级比对逻辑
        for (target in targets) {
            // 1. 绝对精准匹配（忽略大小写）
            if (cleaned.equals(target, ignoreCase = true)) {
                return MatchResult(true, rawText, cleaned, target)
            }

            // 2. 双向包含匹配（如小朋友说“大苹果”，目标为“苹果”；或小朋友说“苹果”，目标为“红苹果”）
            if (cleaned.contains(target, ignoreCase = true) || target.contains(cleaned, ignoreCase = true)) {
                return MatchResult(true, rawText, cleaned, target)
            }

            // 3. 数字形态归一化匹配（中英文数字互转）
            if (normalizeNumbers(cleaned) == normalizeNumbers(target)) {
                return MatchResult(true, rawText, cleaned, target)
            }
        }

        return MatchResult(false, rawText, cleaned, null)
    }

    /**
     * 清洗文本：去除标点、剥离高频前缀、剥离语气词
     */
    fun cleanRecognizedText(input: String): String {
        var text = cleanPunctuationAndSpace(input)

        // 剥离前缀（从长到短匹配）
        for (prefix in PREFIXES_TO_STRIP) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length).trim()
                break
            }
        }

        // 剥离末尾语气词
        while (text.isNotEmpty() && TONE_SUFFIXES.contains(text.last())) {
            text = text.dropLast(1).trim()
        }

        return text
    }

    private fun cleanPunctuationAndSpace(text: String): String {
        return text.replace(Regex("[。？！，、；：“”‘’（）《》·…,.?!;:\"'()\\[\\]\\s]"), "").trim()
    }

    private fun normalizeNumbers(text: String): String {
        return text
            .replace("两", "2")
            .replace("二", "2")
            .replace("一", "1")
            .replace("三", "3")
            .replace("四", "4")
            .replace("五", "5")
            .replace("六", "6")
            .replace("七", "7")
            .replace("八", "8")
            .replace("九", "9")
            .replace("十", "10")
    }
}
