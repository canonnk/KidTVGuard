package com.parent.tvlock.util

/**
 * 家长应急入口触发器：锁屏期间在限定间隔内连按菜单键 [REQUIRED_PRESSES] 次，唤起家长密码验证。
 * 无障碍服务与答题悬浮窗共用同一计数器（按键只会被其中一方先消费，不会重复计数）。
 */
object ParentGateTrigger {
    const val REQUIRED_PRESSES = 5
    const val MAX_GAP_MS = 2000L

    private var count = 0
    private var lastPressAt = 0L

    /**
     * 记录一次菜单键按下，达到连按次数时返回 true（并重置计数）
     */
    @Synchronized
    fun onMenuDown(now: Long = System.currentTimeMillis()): Boolean {
        count = if (lastPressAt > 0L && now - lastPressAt in 0..MAX_GAP_MS) count + 1 else 1
        lastPressAt = now
        if (count >= REQUIRED_PRESSES) {
            reset()
            return true
        }
        return false
    }

    @Synchronized
    fun reset() {
        count = 0
        lastPressAt = 0L
    }
}
