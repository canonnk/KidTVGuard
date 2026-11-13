package com.parent.tvlock

import com.parent.tvlock.util.PenaltyManager
import com.parent.tvlock.util.PenaltyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PenaltyManagerTest {

    private class MemoryStore : PenaltyStore {
        override var penaltyUnlockAt: Long = 0L
        override var penaltyStartAt: Long = 0L
        override var penaltyDurationMs: Long = 0L
        override var attemptQuestionId: Long = 0L
        override var attemptUsed: Int = 0
    }

    private lateinit var store: MemoryStore
    private var now = 1_000_000_000_000L
    private lateinit var manager: PenaltyManager

    @Before
    fun setUp() {
        store = MemoryStore()
        now = 1_000_000_000_000L
        manager = PenaltyManager(store) { now }
    }

    @Test
    fun noPenaltyByDefault() {
        val s = manager.status()
        assertFalse(s.active)
        assertEquals(0L, s.remainingMs)
    }

    @Test
    fun penaltyCountsDownAndExpires() {
        manager.start(5 * 60_000L)
        assertTrue(manager.status().active)
        assertEquals(300L, manager.status().remainingSeconds)

        now += 100_000L
        assertEquals(200L, manager.status().remainingSeconds)

        now += 200_000L
        assertFalse(manager.status().active)
    }

    @Test
    fun penaltySurvivesRestartAndKeepsCounting() {
        manager.start(10 * 60_000L)
        now += 3 * 60_000L
        // 模拟进程被杀/断电重启：同一份持久化数据，新建 manager 实例
        val restarted = PenaltyManager(store) { now }
        assertTrue(restarted.status().active)
        assertEquals(7 * 60L, restarted.status().remainingSeconds)
    }

    @Test
    fun penaltyExpiresWhileTvPoweredOff() {
        manager.start(5 * 60_000L)
        now += 30 * 60_000L // 关机 30 分钟
        val restarted = PenaltyManager(store) { now }
        assertFalse(restarted.status().active)
    }

    @Test
    fun clockRolledBackKeepsLockedWithFullDuration() {
        manager.start(5 * 60_000L)
        now -= 24 * 3600_000L // 系统时间被调早一天（或开机尚未校时）
        val s = manager.status()
        assertTrue(s.active)
        assertTrue(s.clockUnsynced)
        assertEquals(5 * 60_000L, s.remainingMs)
    }

    @Test
    fun smallClockAdjustmentWithinToleranceIsIgnored() {
        manager.start(5 * 60_000L)
        now -= 10_000L
        val s = manager.status()
        assertTrue(s.active)
        assertFalse(s.clockUnsynced)
    }

    @Test
    fun clockSyncedToFutureAfterUnsyncedBootExpiresPenalty() {
        manager.start(5 * 60_000L)
        now = 0L // 开机后时间为 1970
        assertTrue(manager.status().clockUnsynced)
        now = 1_000_000_000_000L + 10 * 60_000L // NTP 校时到真实时间
        assertFalse(manager.status().active)
    }

    @Test
    fun clearPenaltyResetsState() {
        manager.start(5 * 60_000L)
        manager.clearPenalty()
        assertFalse(manager.status().active)
        assertEquals(0L, store.penaltyUnlockAt)
    }

    @Test
    fun zeroDurationDoesNotStartPenalty() {
        manager.start(0L)
        assertFalse(manager.status().active)
    }

    @Test
    fun attemptsArePersistedPerQuestion() {
        assertEquals(0, manager.attemptsUsed(7L))
        manager.saveAttempts(7L, 1)
        assertEquals(1, manager.attemptsUsed(7L))
        assertEquals(0, manager.attemptsUsed(8L))
        assertEquals(7L, manager.pendingQuestionId())

        // 重启后恢复
        val restarted = PenaltyManager(store) { now }
        assertEquals(1, restarted.attemptsUsed(7L))
    }

    @Test
    fun clearAttemptsAndClearAll() {
        manager.saveAttempts(7L, 2)
        manager.start(60_000L)
        manager.clearAttempts()
        assertEquals(0L, manager.pendingQuestionId())
        assertTrue(manager.status().active)

        manager.saveAttempts(7L, 1)
        manager.clearAll()
        assertEquals(0L, manager.pendingQuestionId())
        assertFalse(manager.status().active)
    }
}
