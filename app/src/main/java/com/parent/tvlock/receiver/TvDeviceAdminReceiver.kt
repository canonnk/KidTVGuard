package com.parent.tvlock.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.parent.tvlock.util.L

/**
 * 设备管理员接收器（防止应用被轻易卸载）
 */
class TvDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        L.i("TvDeviceAdminReceiver: Device admin enabled.")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        L.w("TvDeviceAdminReceiver: Device admin disable requested!")
        return "小米电视防沉迷保护服务受到系统设备管理员保护，严禁私自取消激活或卸载应用！"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        L.w("TvDeviceAdminReceiver: Device admin disabled.")
    }
}
