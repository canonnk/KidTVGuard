package com.parent.tvlock.base

import android.app.Application
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.net.SyncManager
import com.parent.tvlock.util.L
import com.parent.tvlock.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局 Application 类
 */
class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        L.i("TvLock Application initialized.")

        // 初始化单例
        PreferenceManager.getInstance(this)
        AppDatabaseHelper.getInstance(this)

        // 启动后台倒计时保活前台服务
        try {
            com.parent.tvlock.service.LockForegroundService.startService(this)
        } catch (e: Exception) {
            L.e("Failed to start LockForegroundService in App.onCreate", tr = e)
        }

        // 异步检测并导入内置兜底题库
        appScope.launch {
            SyncManager.getInstance(this@App).loadDefaultQuestionsIfNeeded()
        }
    }
}
