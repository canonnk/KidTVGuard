package com.parent.tvlock.util

import android.util.Log

/**
 * 轻量日志工具类（具备单元测试未 Mock 环境容错支持）
 */
object L {
    private const val TAG = "TvLock"
    var isDebug = true

    fun d(msg: String, tag: String = TAG) {
        if (!isDebug) return
        try {
            Log.d(tag, msg)
        } catch (e: RuntimeException) {
            println("[$tag/D] $msg")
        }
    }

    fun i(msg: String, tag: String = TAG) {
        try {
            Log.i(tag, msg)
        } catch (e: RuntimeException) {
            println("[$tag/I] $msg")
        }
    }

    fun w(msg: String, tag: String = TAG, tr: Throwable? = null) {
        try {
            Log.w(tag, msg, tr)
        } catch (e: RuntimeException) {
            println("[$tag/W] $msg ${tr?.message ?: ""}")
        }
    }

    fun e(msg: String, tag: String = TAG, tr: Throwable? = null) {
        try {
            Log.e(tag, msg, tr)
        } catch (e: RuntimeException) {
            println("[$tag/E] $msg ${tr?.message ?: ""}")
        }
    }
}
