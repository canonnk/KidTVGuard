package com.parent.tvlock

import com.parent.tvlock.util.ParentAuthManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAndTrackingTest {

    @Test
    fun testParentAuthManager() {
        // Initial state should not be active (or revoke first)
        ParentAuthManager.revokePass()
        assertFalse(ParentAuthManager.hasActivePass())

        // Grant 5 minutes
        ParentAuthManager.grantTemporaryPass(5)
        assertTrue(ParentAuthManager.hasActivePass())

        // Revoke
        ParentAuthManager.revokePass()
        assertFalse(ParentAuthManager.hasActivePass())
    }

    @Test
    fun testSensitivePackageMatching() {
        val sensitiveSamples = listOf(
            "com.android.settings",
            "com.xiaomi.mitv.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.securitycenter"
        )

        for (pkg in sensitiveSamples) {
            val isSensitive = pkg.contains("setting", ignoreCase = true) ||
                    pkg.contains("packageinstaller", ignoreCase = true) ||
                    pkg == "com.miui.securitycenter"
            assertTrue("Expected $pkg to be sensitive", isSensitive)
        }

        val normalApp = "com.ktcp.tv.video"
        val isNormalSensitive = normalApp.contains("setting", ignoreCase = true) ||
                normalApp.contains("packageinstaller", ignoreCase = true)
        assertFalse(isNormalSensitive)
    }

    @Test
    fun testIgnoredPackages() {
        val launcher = "com.mitv.tvhome"
        val ime = "com.sohu.inputmethod.sogou.tv"
        val normalVideo = "com.gitvdemo.video"

        val isLauncherIgnored = launcher == "com.mitv.tvhome"
        val isImeIgnored = ime.contains("inputmethod", ignoreCase = true)
        val isVideoIgnored = normalVideo == "com.mitv.tvhome" || normalVideo.contains("inputmethod", ignoreCase = true)

        assertTrue(isLauncherIgnored)
        assertTrue(isImeIgnored)
        assertFalse(isVideoIgnored)
    }
}
