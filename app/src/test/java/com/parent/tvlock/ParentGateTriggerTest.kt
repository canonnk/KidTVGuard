package com.parent.tvlock

import com.parent.tvlock.util.ParentGateTrigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParentGateTriggerTest {

    @Before
    fun setUp() {
        ParentGateTrigger.reset()
    }

    @Test
    fun fivePressesWithinGapTrigger() {
        var t = 10_000L
        repeat(4) {
            assertFalse(ParentGateTrigger.onMenuDown(t))
            t += 500L
        }
        assertTrue(ParentGateTrigger.onMenuDown(t))
    }

    @Test
    fun longGapResetsCount() {
        var t = 10_000L
        repeat(4) {
            assertFalse(ParentGateTrigger.onMenuDown(t))
            t += 500L
        }
        t += 5_000L // 间隔过长，重新计数
        assertFalse(ParentGateTrigger.onMenuDown(t))
        repeat(3) {
            t += 500L
            assertFalse(ParentGateTrigger.onMenuDown(t))
        }
        t += 500L
        assertTrue(ParentGateTrigger.onMenuDown(t))
    }

    @Test
    fun triggerResetsCounterAfterFiring() {
        var t = 10_000L
        repeat(4) { ParentGateTrigger.onMenuDown(t); t += 100L }
        assertTrue(ParentGateTrigger.onMenuDown(t))
        t += 100L
        assertFalse(ParentGateTrigger.onMenuDown(t))
    }
}
