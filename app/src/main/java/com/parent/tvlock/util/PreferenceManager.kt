package com.parent.tvlock.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 状态与轻量配置管理器
 */
class PreferenceManager private constructor(context: Context) {

    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "tv_lock_preferences"

        // 键名定义
        private const val KEY_IS_LOCKED = "is_locked"
        private const val KEY_LOCK_INTERVAL_MINUTES = "lock_interval_minutes"
        private const val KEY_SYNC_SERVER_URL = "sync_server_url"
        private const val KEY_PARENT_PASSWORD = "parent_password"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LOCK_TARGET_TIMESTAMP = "lock_target_timestamp"

        // 默认配置
        const val DEFAULT_LOCK_INTERVAL_MINUTES = 10
        const val DEFAULT_SYNC_SERVER_URL = "http://127.0.0.1:3000/questions.json"
        const val DEFAULT_PARENT_PASSWORD = "888888"

        @Volatile
        private var instance: PreferenceManager? = null

        fun getInstance(context: Context): PreferenceManager {
            return instance ?: synchronized(this) {
                instance ?: PreferenceManager(context).also { instance = it }
            }
        }
    }

    /**
     * 是否处于锁屏状态（断电死锁标志）
     */
    var isLocked: Boolean
        get() = sp.getBoolean(KEY_IS_LOCKED, false)
        set(value) = sp.edit().putBoolean(KEY_IS_LOCKED, value).apply()

    /**
     * 防沉迷触发倒计时（单位：分钟）
     */
    var lockIntervalMinutes: Int
        get() = sp.getInt(KEY_LOCK_INTERVAL_MINUTES, DEFAULT_LOCK_INTERVAL_MINUTES)
        set(value) = sp.edit().putInt(KEY_LOCK_INTERVAL_MINUTES, value).apply()

    /**
     * 题库远程同步 URL
     */
    var syncServerUrl: String
        get() = sp.getString(KEY_SYNC_SERVER_URL, DEFAULT_SYNC_SERVER_URL) ?: DEFAULT_SYNC_SERVER_URL
        set(value) = sp.edit().putString(KEY_SYNC_SERVER_URL, value).apply()

    /**
     * 家长解锁/防卸载密码
     */
    var parentPassword: String
        get() = sp.getString(KEY_PARENT_PASSWORD, DEFAULT_PARENT_PASSWORD) ?: DEFAULT_PARENT_PASSWORD
        set(value) = sp.edit().putString(KEY_PARENT_PASSWORD, value).apply()

    /**
     * 上次同步时间戳
     */
    var lastSyncTime: Long
        get() = sp.getLong(KEY_LAST_SYNC_TIME, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()

    /**
     * 当前周期的锁定目标时间戳（物理绝对时间，单位：毫秒）
     * 用于防止进程杀后台、退出或重启后倒计时被重置刷新
     */
    var lockTargetTimestamp: Long
        get() = sp.getLong(KEY_LOCK_TARGET_TIMESTAMP, 0L)
        set(value) = sp.edit().putLong(KEY_LOCK_TARGET_TIMESTAMP, value).apply()
}
