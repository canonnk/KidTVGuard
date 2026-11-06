package com.parent.tvlock

import com.parent.tvlock.util.DateTimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateTimeUtilsTest {

    @Test
    fun testFormatDuration() {
        assertEquals("45秒", DateTimeUtils.formatDuration(45))
        assertEquals("1分钟", DateTimeUtils.formatDuration(60))
        assertEquals("1分30秒", DateTimeUtils.formatDuration(90))
        assertEquals("1小时", DateTimeUtils.formatDuration(3600))
        assertEquals("1小时15分钟", DateTimeUtils.formatDuration(4500))
    }

    @Test
    fun testGetTodayDate() {
        val today = DateTimeUtils.getTodayDate()
        assertTrue(today.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }
}
