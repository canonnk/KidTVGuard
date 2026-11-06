package com.parent.tvlock

import com.parent.tvlock.service.LockForegroundService
import com.parent.tvlock.util.DateTimeUtils
import com.parent.tvlock.util.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ServiceAndDeadlockTest {

    @Test
    fun testDefaultLockIntervalAndConstants() {
        assertEquals(10, PreferenceManager.DEFAULT_LOCK_INTERVAL_MINUTES)
        assertEquals("http://127.0.0.1:3000/questions.json", PreferenceManager.DEFAULT_SYNC_SERVER_URL)
        assertEquals("888888", PreferenceManager.DEFAULT_PARENT_PASSWORD)

        // 10 minutes should be 600 seconds
        val seconds = PreferenceManager.DEFAULT_LOCK_INTERVAL_MINUTES * 60L
        assertEquals(600L, seconds)
        assertEquals("10分钟", DateTimeUtils.formatDuration(seconds))
    }

    @Test
    fun testIntentActionConstants() {
        assertNotNull(LockForegroundService.ACTION_START)
        assertNotNull(LockForegroundService.ACTION_RESET_COUNTDOWN)
        assertNotNull(LockForegroundService.ACTION_LOCK_NOW)
        assertNotNull(LockForegroundService.ACTION_UNLOCK)
        assertNotNull(LockForegroundService.ACTION_COUNTDOWN_TICK)
    }
}
