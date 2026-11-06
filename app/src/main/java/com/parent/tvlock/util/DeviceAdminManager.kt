package com.parent.tvlock.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.parent.tvlock.receiver.TvDeviceAdminReceiver

/**
 * 设备管理器状态与跳转配置辅助工具
 */
object DeviceAdminManager {

    /**
     * 获取设备管理员组件名称
     */
    fun getComponentName(context: Context): ComponentName {
        return ComponentName(context.applicationContext, TvDeviceAdminReceiver::class.java)
    }

    /**
     * 检查当前是否已成功激活设备管理员
     */
    fun isAdminActive(context: Context): Boolean {
        val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        return dpm?.isAdminActive(getComponentName(context)) ?: false
    }

    /**
     * 构建跳转至系统激活设备管理员界面的 Intent
     */
    fun buildEnableAdminIntent(
        context: Context,
        explanation: String = "激活设备管理器后，未成年人将无法直接在应用管理中卸载本防沉迷锁屏服务。"
    ): Intent {
        val componentName = getComponentName(context)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
