package com.parent.tvlock.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间与日期格式化工具类
 */
object DateTimeUtils {
    private const val PATTERN_DATE = "yyyy-MM-dd"
    private const val PATTERN_DATE_TIME = "yyyy-MM-dd HH:mm:ss"
    private const val PATTERN_TIME = "HH:mm:ss"

    fun getTodayDate(): String {
        return formatDate(System.currentTimeMillis(), PATTERN_DATE)
    }

    fun formatDate(timestamp: Long, pattern: String = PATTERN_DATE): String {
        return try {
            val sdf = SimpleDateFormat(pattern, Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            ""
        }
    }

    fun formatDateTime(timestamp: Long): String {
        return formatDate(timestamp, PATTERN_DATE_TIME)
    }

    fun formatTime(timestamp: Long): String {
        return formatDate(timestamp, PATTERN_TIME)
    }

    /**
     * 将秒数转换为友好的显示文本（如 "1小时30分钟" 或 "45秒"）
     */
    fun formatDuration(durationSeconds: Long): String {
        if (durationSeconds < 60) {
            return "${durationSeconds}秒"
        }
        val minutes = durationSeconds / 60
        val remainingSeconds = durationSeconds % 60
        if (minutes < 60) {
            return if (remainingSeconds > 0) {
                "${minutes}分${remainingSeconds}秒"
            } else {
                "${minutes}分钟"
            }
        }
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return if (remainingMinutes > 0) {
            "${hours}小时${remainingMinutes}分钟"
        } else {
            "${hours}小时"
        }
    }
}
