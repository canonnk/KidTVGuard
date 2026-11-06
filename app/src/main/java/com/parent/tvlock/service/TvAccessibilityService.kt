package com.parent.tvlock.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.dao.AppUsageRecordDao
import com.parent.tvlock.db.model.AppUsageRecord
import com.parent.tvlock.overlay.ParentPasswordOverlayView
import com.parent.tvlock.util.DateTimeUtils
import com.parent.tvlock.util.L
import com.parent.tvlock.util.ParentAuthManager
import com.parent.tvlock.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 电视无障碍服务：防逃逸按键拦截、系统设置保护与电视活动时长监控
 */
class TvAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var appUsageRecordDao: AppUsageRecordDao
    private lateinit var windowManager: WindowManager

    // 应用时长追踪状态
    private var currentForegroundPkg: String? = null
    private var currentForegroundAppName: String? = null
    private var currentStartTime: Long = 0L

    // 家长密码拦截弹窗状态
    private var passwordOverlayView: View? = null
    private var isPasswordOverlayShowing: Boolean = false

    private var screenOffReceiver: BroadcastReceiver? = null

    companion object {
        @Volatile
        var instance: TvAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

        // 敏感系统设置与应用卸载安装包名
        private val SENSITIVE_PACKAGES = setOf(
            "com.android.settings",
            "com.xiaomi.mitv.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.miui.securitycenter"
        )

        // 桌面 Launcher 与系统组件过滤白名单（不计入第三方 App 使用时长）
        private val IGNORE_PACKAGES = setOf(
            "com.mitv.tvhome",
            "com.xiaomi.mitv.tvhome",
            "com.google.android.tvlauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.android.systemui"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        L.i("TvAccessibilityService connected.")

        preferenceManager = PreferenceManager.getInstance(this)
        appUsageRecordDao = AppUsageRecordDao(AppDatabaseHelper.getInstance(this))
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        registerScreenOffReceiver()
    }

    /**
     * 关键：底层按键拦截（锁屏期间强制拦截 Home 键与返回键）
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (preferenceManager.isLocked) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_BACK) {
                L.d("Intercepted key event in AccessibilityService: $keyCode")
                return true // 拦截并消耗按键，防止孩子按 Home 逃逸锁屏
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString() ?: return
            handleWindowStateChanged(pkgName)
        }
    }

    private fun handleWindowStateChanged(pkgName: String) {
        // 0. 守护检查：确保前台倒计时服务常驻，且物理到期时间一到立即死锁
        ensureGuardServiceAlive()

        // 1. 系统设置防逃逸与卸载拦截
        checkAndInterceptSensitiveSettings(pkgName)

        // 2. 电视日常活动与应用使用时长追踪
        trackAppUsage(pkgName)
    }

    private fun ensureGuardServiceAlive() {
        try {
            if (!LockForegroundService.isRunning) {
                L.i("Accessibility detected LockForegroundService not running. Reviving...")
                LockForegroundService.startService(this)
            }
            val target = preferenceManager.lockTargetTimestamp
            if (target > 0L && System.currentTimeMillis() >= target && !preferenceManager.isLocked) {
                L.w("Accessibility watchdog detected physical deadline expired. Forcing lock!")
                LockForegroundService.lockImmediately(this)
            }
        } catch (e: Exception) {
            L.w("Failed to ensure guard service alive", tr = e)
        }
    }

    /**
     * 检查并拦截敏感设置与卸载界面
     */
    private fun checkAndInterceptSensitiveSettings(pkgName: String) {
        // 若处于家长临时放行期，不弹出密码拦截
        if (ParentAuthManager.hasActivePass()) return

        if (isSensitivePackage(pkgName)) {
            L.w("Sensitive package access detected: $pkgName. Showing parent password overlay.")
            showPasswordOverlay(pkgName)
        }
    }

    private fun isSensitivePackage(pkgName: String): Boolean {
        if (SENSITIVE_PACKAGES.contains(pkgName)) return true
        if (pkgName.contains("setting", ignoreCase = true) && !pkgName.contains(packageName)) {
            return true
        }
        if (pkgName.contains("packageinstaller", ignoreCase = true)) {
            return true
        }
        return false
    }

    /**
     * 弹出全屏密码拦截窗
     */
    private fun showPasswordOverlay(interceptedPkg: String) {
        if (isPasswordOverlayShowing && passwordOverlayView != null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // 没有悬浮窗权限时：
            // 若当前正处于倒计时结束的强制死锁状态，强制退回桌面严防逃逸；
            // 若当前未锁屏（例如正在初始授权配置阶段），允许家长在系统设置中操作，避免死锁踢出
            if (preferenceManager.isLocked) {
                L.w("Overlay permission missing during active lock! Bouncing back to Home.")
                performGlobalAction(GLOBAL_ACTION_HOME)
            } else {
                L.d("Overlay permission missing but TV is not locked. Allowing settings configuration.")
            }
            return
        }

        try {
            @Suppress("DEPRECATION")
            val layoutParams = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.CENTER
                flags = WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            }

            val overlayView = ParentPasswordOverlayView(this)
            overlayView.setOnPasswordAuthListener(object : ParentPasswordOverlayView.OnPasswordAuthListener {
                override fun onAuthSuccess() {
                    L.i("Parent password auth succeeded. Allowing access to: $interceptedPkg")
                    dismissPasswordOverlay()
                }

                override fun onAuthCancelled() {
                    L.i("Parent password auth cancelled. Bouncing back to Home.")
                    dismissPasswordOverlay()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            })

            windowManager.addView(overlayView, layoutParams)
            passwordOverlayView = overlayView
            isPasswordOverlayShowing = true
        } catch (e: Exception) {
            L.e("Failed to show password overlay", tr = e)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun dismissPasswordOverlay() {
        try {
            passwordOverlayView?.let {
                windowManager.removeView(it)
            }
        } catch (e: Exception) {
            L.e("Failed to remove password overlay", tr = e)
        } finally {
            passwordOverlayView = null
            isPasswordOverlayShowing = false
        }
    }

    /**
     * 电视应用活动使用时长记录追踪算法
     */
    private fun trackAppUsage(newPkgName: String) {
        // 如果是本应用自身或未发生应用切换，则忽略
        if (newPkgName == packageName || newPkgName == currentForegroundPkg) return

        val currentTime = System.currentTimeMillis()

        // 结算前一个应用的使用时长
        currentForegroundPkg?.let { prevPkg ->
            if (currentStartTime > 0) {
                val durationSeconds = (currentTime - currentStartTime) / 1000L
                // 过滤 5 秒内闪现的弹窗与瞬时活动
                if (durationSeconds >= 5L) {
                    val appName = currentForegroundAppName ?: getAppNameForPackage(prevPkg)
                    val recordDate = DateTimeUtils.formatDate(currentStartTime)

                    val record = AppUsageRecord(
                        packageName = prevPkg,
                        appName = appName,
                        startTime = currentStartTime,
                        endTime = currentTime,
                        durationSeconds = durationSeconds,
                        recordDate = recordDate
                    )

                    serviceScope.launch(Dispatchers.IO) {
                        appUsageRecordDao.insert(record)
                        L.i("Recorded TV app usage: $appName ($prevPkg) used for ${durationSeconds}s on $recordDate")
                    }
                }
            }
        }

        // 开启新应用的时长统计（过滤桌面与输入法等白名单）
        if (!isIgnoredPackage(newPkgName)) {
            currentForegroundPkg = newPkgName
            currentForegroundAppName = getAppNameForPackage(newPkgName)
            currentStartTime = currentTime
            L.d("Tracking new foreground TV app: $currentForegroundAppName ($newPkgName)")
        } else {
            // 切回了电视桌面或输入法，重置统计指针
            currentForegroundPkg = null
            currentForegroundAppName = null
            currentStartTime = 0L
        }
    }

    /**
     * 判断是否为桌面 Launcher、输入法或系统组件
     */
    private fun isIgnoredPackage(pkgName: String): Boolean {
        if (IGNORE_PACKAGES.contains(pkgName)) return true
        if (pkgName.contains("inputmethod", ignoreCase = true) ||
            pkgName.contains("pinyin", ignoreCase = true) ||
            pkgName.contains("keyboard", ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    private fun getAppNameForPackage(pkgName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkgName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkgName
        }
    }

    /**
     * 关屏时自动结算当前正在运行的应用时长
     */
    private fun registerScreenOffReceiver() {
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    L.i("Screen OFF received in AccessibilityService, settling current app usage.")
                    currentForegroundPkg?.let { pkg ->
                        val now = System.currentTimeMillis()
                        val duration = (now - currentStartTime) / 1000L
                        if (duration >= 5L) {
                            val appName = currentForegroundAppName ?: getAppNameForPackage(pkg)
                            val record = AppUsageRecord(
                                packageName = pkg,
                                appName = appName,
                                startTime = currentStartTime,
                                endTime = now,
                                durationSeconds = duration,
                                recordDate = DateTimeUtils.formatDate(currentStartTime)
                            )
                            serviceScope.launch(Dispatchers.IO) {
                                appUsageRecordDao.insert(record)
                            }
                        }
                    }
                    currentForegroundPkg = null
                    currentStartTime = 0L
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)
    }

    override fun onInterrupt() {
        L.w("TvAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        L.i("TvAccessibilityService destroyed.")
        instance = null
        serviceScope.cancel()
        dismissPasswordOverlay()
        try {
            screenOffReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            L.w("Failed to unregister screen off receiver", tr = e)
        }
    }
}
