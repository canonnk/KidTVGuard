package com.parent.tvlock.util

/**
 * 家长密码临时授权状态管理器（验证成功后在指定时间内允许家长配置系统）
 */
object ParentAuthManager {
    private var tempPassUntil: Long = 0L

    /**
     * 授予临时通行凭据（默认 5 分钟）
     */
    fun grantTemporaryPass(minutes: Int = 5) {
        tempPassUntil = System.currentTimeMillis() + (minutes * 60 * 1000L)
        L.i("Granted temporary parent pass for $minutes minutes.")
    }

    /**
     * 当前是否在家长临时通行期内
     */
    fun hasActivePass(): Boolean {
        return System.currentTimeMillis() < tempPassUntil
    }

    /**
     * 清除通行证
     */
    fun revokePass() {
        tempPassUntil = 0L
    }
}
