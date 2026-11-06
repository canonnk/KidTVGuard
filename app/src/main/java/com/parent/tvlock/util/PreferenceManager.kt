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
        private const val KEY_VOICE_INTERACTION_MODE = "voice_interaction_mode"
        private const val KEY_VOICE_MAX_DURATION_SECONDS = "voice_max_duration_seconds"
        private const val KEY_BAIDU_ASR_API_KEY = "baidu_asr_api_key"
        private const val KEY_BAIDU_ASR_SECRET_KEY = "baidu_asr_secret_key"
        private const val KEY_VOICE_RETRY_COUNT = "voice_retry_count"

        // 交互模式常量
        const val VOICE_MODE_PUSH_TO_TALK = 0 // 对讲机模式：按住说话，松开提交
        const val VOICE_MODE_CLICK_TO_RECORD = 1 // 单击模式：按一下开始录音，再按或超时自动提交

        // 默认配置
        const val DEFAULT_LOCK_INTERVAL_MINUTES = 10
        const val DEFAULT_SYNC_SERVER_URL = "http://127.0.0.1:3000/questions.json"
        const val DEFAULT_PARENT_PASSWORD = "888888"
        const val DEFAULT_VOICE_INTERACTION_MODE = VOICE_MODE_PUSH_TO_TALK
        const val DEFAULT_VOICE_MAX_DURATION_SECONDS = 4
        const val DEFAULT_VOICE_RETRY_COUNT = 2
        // 内置默认百度语音测试 Key（供直接体验，可在家长设置中随时替换为您自己的商业/个人 Key）
        const val DEFAULT_BAIDU_ASR_API_KEY = "s58hG8cO277b21E0hG02gB1k"
        const val DEFAULT_BAIDU_ASR_SECRET_KEY = "d6eF91g82h0b021gA1k092hB71g021kA"

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

    /**
     * 语音交互模式：0 对讲机模式（按住说话），1 单击模式（按一下开始）
     */
    var voiceInteractionMode: Int
        get() = sp.getInt(KEY_VOICE_INTERACTION_MODE, DEFAULT_VOICE_INTERACTION_MODE)
        set(value) = sp.edit().putInt(KEY_VOICE_INTERACTION_MODE, value).apply()

    /**
     * 语音最大录音持续时长（单位：秒）
     */
    var voiceMaxDurationSeconds: Int
        get() = sp.getInt(KEY_VOICE_MAX_DURATION_SECONDS, DEFAULT_VOICE_MAX_DURATION_SECONDS)
        set(value) = sp.edit().putInt(KEY_VOICE_MAX_DURATION_SECONDS, value).apply()

    /**
     * 百度短语音识别极速版 API Key
     */
    var baiduAsrApiKey: String
        get() = sp.getString(KEY_BAIDU_ASR_API_KEY, DEFAULT_BAIDU_ASR_API_KEY) ?: DEFAULT_BAIDU_ASR_API_KEY
        set(value) = sp.edit().putString(KEY_BAIDU_ASR_API_KEY, value).apply()

    /**
     * 百度短语音识别极速版 Secret Key
     */
    var baiduAsrSecretKey: String
        get() = sp.getString(KEY_BAIDU_ASR_SECRET_KEY, DEFAULT_BAIDU_ASR_SECRET_KEY) ?: DEFAULT_BAIDU_ASR_SECRET_KEY
        set(value) = sp.edit().putString(KEY_BAIDU_ASR_SECRET_KEY, value).apply()

    /**
     * 语音答题未听清时的允许重试次数（默认 2 次）
     */
    var voiceRetryCount: Int
        get() = sp.getInt(KEY_VOICE_RETRY_COUNT, DEFAULT_VOICE_RETRY_COUNT)
        set(value) = sp.edit().putInt(KEY_VOICE_RETRY_COUNT, value).apply()
}
