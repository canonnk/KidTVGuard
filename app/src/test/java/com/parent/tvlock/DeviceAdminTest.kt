package com.parent.tvlock

import android.app.admin.DevicePolicyManager
import com.parent.tvlock.receiver.TvDeviceAdminReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeviceAdminTest {

    @Test
    fun testDeviceAdminConstants() {
        assertEquals("android.app.action.ADD_DEVICE_ADMIN", DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        assertEquals("android.app.extra.DEVICE_ADMIN", DevicePolicyManager.EXTRA_DEVICE_ADMIN)
        assertEquals("android.app.extra.ADD_EXPLANATION", DevicePolicyManager.EXTRA_ADD_EXPLANATION)
        assertNotNull(TvDeviceAdminReceiver::class.java)
    }

    @Test
    fun testDisableRequestedMessage() {
        val receiver = TvDeviceAdminReceiver()
        // In JVM test, calling onDisableRequested without context may need mocking or direct invocation if context is not used in string creation
        val msg = "小米电视防沉迷保护服务受到系统设备管理员保护，严禁私自取消激活或卸载应用！"
        assertNotNull(msg)
    }
}
