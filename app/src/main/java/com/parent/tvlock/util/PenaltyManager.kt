package com.parent.tvlock.util

import android.content.Context

/**
 * 惩罚机制运行时状态存储抽象（生产环境由 [PreferenceManager] 实现并同步落盘，单测使用内存实现）
 */
interface PenaltyStore {
    /** 惩罚解锁的绝对时间戳（真实时间，毫秒），0 表示当前无惩罚 */
    var penaltyUnlockAt: Long

    /** 惩罚开始时间戳，用于识别系统时间被回拨/尚未校时 */
    var penaltyStartAt: Long

    /** 惩罚总时长（毫秒） */
    var penaltyDurationMs: Long

    /** 当前正在作答题目 ID（用于重启后沿用同一题与已用次数），0 表示无 */
    var attemptQuestionId: Long

    /** 当前题已用作答次数 */
    var attemptUsed: Int
}

/**
 * 答错惩罚管理器：
 * 1. 惩罚锁定：基于真实时间的绝对解锁时间戳，断电/关机/重启后继续计时；
 * 2. 作答进度：持久化当前题与已用次数，防止通过重启刷新次数或刷题。
 */
class PenaltyManager(
    private val store: PenaltyStore,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * 惩罚状态快照
     * @param active 是否处于惩罚中
     * @param remainingMs 剩余毫秒数
     * @param clockUnsynced 系统时间早于惩罚开始时间（被回拨或开机尚未校时），此时保持锁定并等待时间恢复
     */
    data class Status(
        val active: Boolean,
        val remainingMs: Long,
        val clockUnsynced: Boolean = false
    ) {
        val remainingSeconds: Long get() = (remainingMs + 999L) / 1000L
    }

    companion object {
        /** 时间回拨容差：小幅校时不视为篡改 */
        const val CLOCK_ROLLBACK_TOLERANCE_MS = 60_000L

        @Volatile
        private var instance: PenaltyManager? = null

        fun getInstance(context: Context): PenaltyManager {
            return instance ?: synchronized(this) {
                instance ?: PenaltyManager(PreferenceManager.getInstance(context)).also { instance = it }
            }
        }
    }

    // ======================= 惩罚锁定 =======================

    /**
     * 开始一次惩罚。必须在更新 UI 之前调用（内部同步落盘）。
     */
    @Synchronized
    fun start(durationMs: Long) {
        if (durationMs <= 0L) return
        val now = clock()
        store.penaltyDurationMs = durationMs
        store.penaltyStartAt = now
        store.penaltyUnlockAt = now + durationMs
    }

    @Synchronized
    fun status(): Status {
        val unlockAt = store.penaltyUnlockAt
        if (unlockAt <= 0L) return Status(active = false, remainingMs = 0L)

        val now = clock()
        val startAt = store.penaltyStartAt
        val duration = store.penaltyDurationMs

        // 系统时间早于惩罚开始时间：时间被回拨或开机后尚未校时，保持锁定，剩余时间显示为完整时长
        if (startAt > 0L && now < startAt - CLOCK_ROLLBACK_TOLERANCE_MS) {
            return Status(active = true, remainingMs = duration.coerceAtLeast(0L), clockUnsynced = true)
        }

        val remaining = unlockAt - now
        return if (remaining > 0L) {
            Status(active = true, remainingMs = remaining)
        } else {
            Status(active = false, remainingMs = 0L)
        }
    }

    fun isActive(): Boolean = status().active

    /**
     * 清除惩罚状态（到期、家长手动解除时调用）
     */
    @Synchronized
    fun clearPenalty() {
        if (store.penaltyUnlockAt != 0L) store.penaltyUnlockAt = 0L
        if (store.penaltyStartAt != 0L) store.penaltyStartAt = 0L
        if (store.penaltyDurationMs != 0L) store.penaltyDurationMs = 0L
    }

    // ======================= 作答进度 =======================

    /** 指定题目已用次数（非当前持久化题目返回 0） */
    @Synchronized
    fun attemptsUsed(questionId: Long): Int {
        return if (questionId > 0L && store.attemptQuestionId == questionId) store.attemptUsed else 0
    }

    /** 有未完成作答进度的题目 ID，没有则返回 0 */
    @Synchronized
    fun pendingQuestionId(): Long {
        return if (store.attemptQuestionId > 0L && store.attemptUsed > 0) store.attemptQuestionId else 0L
    }

    @Synchronized
    fun saveAttempts(questionId: Long, used: Int) {
        store.attemptQuestionId = questionId
        store.attemptUsed = used
    }

    @Synchronized
    fun clearAttempts() {
        if (store.attemptQuestionId != 0L) store.attemptQuestionId = 0L
        if (store.attemptUsed != 0) store.attemptUsed = 0
    }

    /** 清除全部惩罚相关状态（答对解锁、家长手动解锁） */
    @Synchronized
    fun clearAll() {
        clearPenalty()
        clearAttempts()
    }
}
