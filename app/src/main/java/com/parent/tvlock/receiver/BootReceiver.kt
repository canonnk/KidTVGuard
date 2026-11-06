package com.parent.tvlock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.parent.tvlock.overlay.OverlayLockManager
import com.parent.tvlock.service.LockForegroundService
import com.parent.tvlock.util.L
import com.parent.tvlock.util.PreferenceManager

/**
 * 开机自启动与断电死锁拦截广播接收器
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        L.i("BootReceiver received action: $action")

        val preferenceManager = PreferenceManager.getInstance(context)
        val overlayLockManager = OverlayLockManager.getInstance(context)

        // 核心：断电死锁逻辑
        // 若断电前处于锁屏状态（未通过答题解锁），开机即刻强制重新弹出锁屏悬浮窗
        if (preferenceManager.isLocked) {
            L.w("Power-off deadlock detected: TV was locked before reboot! Re-locking immediately.")
            overlayLockManager.showLock()
        }

        // 启动/拉起常驻前台服务继续倒计时守护
        try {
            LockForegroundService.startService(context)
            L.i("LockForegroundService started from BootReceiver.")
        } catch (e: Exception) {
            L.e("Failed to start LockForegroundService from BootReceiver", tr = e)
        }
    }
}
