package com.parent.tvlock

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.dao.AnswerRecordDao
import com.parent.tvlock.db.dao.AppUsageRecordDao
import com.parent.tvlock.db.dao.WrongQuestionDao
import com.parent.tvlock.net.SyncManager
import com.parent.tvlock.service.LockForegroundService
import com.parent.tvlock.service.TvAccessibilityService
import com.parent.tvlock.ui.AppUsageStatsActivity
import com.parent.tvlock.ui.QuestionHistoryActivity
import com.parent.tvlock.ui.WrongQuestionsActivity
import com.parent.tvlock.util.DateTimeUtils
import com.parent.tvlock.util.DeviceAdminManager
import com.parent.tvlock.util.FocusHelper
import com.parent.tvlock.util.L
import com.parent.tvlock.util.ParentAuthManager
import com.parent.tvlock.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 家长控制中心与主设置面板（采用 Apple iOS / tvOS Inset Grouped 美学重构）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var syncManager: SyncManager

    private lateinit var tvServiceStatus: TextView
    private lateinit var btnPermissionOverlay: View
    private lateinit var tvStatusOverlay: TextView
    private lateinit var btnPermissionAccessibility: View
    private lateinit var tvStatusAccessibility: TextView
    private lateinit var btnPermissionDeviceAdmin: View
    private lateinit var tvStatusDeviceAdmin: TextView

    private lateinit var tvCurrentIntervalText: TextView
    private lateinit var tvCurrentServerUrl: TextView

    private lateinit var tvAnswerStatsBrief: TextView
    private lateinit var tvWrongCountBrief: TextView
    private lateinit var tvUsageStatsBrief: TextView

    private var countdownReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferenceManager = PreferenceManager.getInstance(this)
        syncManager = SyncManager.getInstance(this)

        initViews()
        setupListeners()
        registerCountdownReceiver()

        // 确保前台守护服务运行中
        LockForegroundService.startService(this)
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndUpdateUi()
        refreshDashboardBriefStats()
        tvCurrentServerUrl.text = preferenceManager.syncServerUrl
    }

    private fun initViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)

        btnPermissionOverlay = findViewById(R.id.btnPermissionOverlay)
        tvStatusOverlay = findViewById(R.id.tvStatusOverlay)

        btnPermissionAccessibility = findViewById(R.id.btnPermissionAccessibility)
        tvStatusAccessibility = findViewById(R.id.tvStatusAccessibility)

        btnPermissionDeviceAdmin = findViewById(R.id.btnPermissionDeviceAdmin)
        tvStatusDeviceAdmin = findViewById(R.id.tvStatusDeviceAdmin)

        tvCurrentIntervalText = findViewById(R.id.tvCurrentIntervalText)
        tvCurrentServerUrl = findViewById(R.id.tvCurrentServerUrl)

        tvAnswerStatsBrief = findViewById(R.id.tvAnswerStatsBrief)
        tvWrongCountBrief = findViewById(R.id.tvWrongCountBrief)
        tvUsageStatsBrief = findViewById(R.id.tvUsageStatsBrief)

        // 独立操作大按钮：采用带安全缓冲的微缩放动效
        FocusHelper.setupFocusScale(findViewById(R.id.btnQuickLock), scale = 1.025f)

        // 卡片内部内嵌行与分段选择器：严格收敛于卡片内圆角，使用内嵌描边与平滑高亮（scale = 1.0f）
        FocusHelper.setupCardRowFocusBatch(
            btnPermissionOverlay,
            btnPermissionAccessibility,
            btnPermissionDeviceAdmin,
            findViewById(R.id.btnInterval5),
            findViewById(R.id.btnInterval10),
            findViewById(R.id.btnInterval15),
            findViewById(R.id.btnInterval30),
            findViewById(R.id.btnInterval60),
            findViewById(R.id.btnSyncQuestions),
            findViewById(R.id.btnConfigServerUrl),
            findViewById(R.id.btnChangePassword),
            findViewById(R.id.btnCardAnswerHistory),
            findViewById(R.id.btnCardWrongBook),
            findViewById(R.id.btnCardAppUsage)
        )

        // 默认将遥控器焦点定于左侧主卡片的锁屏测试按钮
        FocusHelper.requestFocusSafe(findViewById(R.id.btnQuickLock))
        updateIntervalSelectionUi()
    }

    private fun setupListeners() {
        // 立即锁屏测试
        findViewById<View>(R.id.btnQuickLock).setOnClickListener {
            L.i("Manual lock screen triggered by parent.")
            LockForegroundService.lockImmediately(this)
        }

        // 1. 悬浮窗授权跳转
        btnPermissionOverlay.setOnClickListener {
            openOverlayPermissionSettings()
        }

        // 2. 无障碍授权跳转
        btnPermissionAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // 3. 设备管理员授权跳转
        btnPermissionDeviceAdmin.setOnClickListener {
            openDeviceAdminSettings()
        }

        // 倒计时时长分段选择器
        val intervalButtons = mapOf(
            R.id.btnInterval5 to 5,
            R.id.btnInterval10 to 10,
            R.id.btnInterval15 to 15,
            R.id.btnInterval30 to 30,
            R.id.btnInterval60 to 60
        )
        for ((viewId, minutes) in intervalButtons) {
            findViewById<View>(viewId).setOnClickListener {
                preferenceManager.lockIntervalMinutes = minutes
                LockForegroundService.startCountdown(this)
                updateIntervalSelectionUi()
                Toast.makeText(this, "已设定每次观看限额为 $minutes 分钟", Toast.LENGTH_SHORT).show()
            }
        }

        // 立即同步题库
        findViewById<View>(R.id.btnSyncQuestions).setOnClickListener {
            performSync()
        }

        // 修改同步地址
        findViewById<View>(R.id.btnConfigServerUrl).setOnClickListener {
            showConfigServerUrlDialog()
        }

        // 修改家长密码
        findViewById<View>(R.id.btnChangePassword).setOnClickListener {
            showChangePasswordDialog()
        }

        // 数据审计看板入口
        findViewById<View>(R.id.btnCardAnswerHistory).setOnClickListener {
            startActivity(Intent(this, QuestionHistoryActivity::class.java))
        }
        findViewById<View>(R.id.btnCardWrongBook).setOnClickListener {
            startActivity(Intent(this, WrongQuestionsActivity::class.java))
        }
        findViewById<View>(R.id.btnCardAppUsage).setOnClickListener {
            startActivity(Intent(this, AppUsageStatsActivity::class.java))
        }
    }

    private var lastPermissionClickTime: Long = 0L

    /**
     * 防快速双击抖动（防止电视遥控器快速连按 OK 导致重复发起 Intent 或系统异常）
     */
    private fun isFastDoubleClick(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPermissionClickTime < 600L) {
            return true
        }
        lastPermissionClickTime = now
        return false
    }

    /**
     * 1. 安全跳转悬浮窗权限配置页（针对 Android TV / 小米电视 Leanback 深度适配多级降级机制）
     */
    private fun openOverlayPermissionSettings() {
        if (isFastDoubleClick()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // 核心安全前置：给家长临时放行 5 分钟无障碍拦截，防止刚打开设置就被无障碍服务拦截闪退回桌面
            ParentAuthManager.grantTemporaryPass(5)

            val candidateIntents = listOf(
                // 方案 A：标准手机悬浮窗权限管理页（指定包名）
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                // 方案 B：标准悬浮窗管理列表页
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                // 方案 C：应用详细信息设置页（指定包名）
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                // 方案 D：Android TV 原生应用管理详情页 (com.android.tv.settings)
                Intent().apply {
                    component = ComponentName("com.android.tv.settings", "com.android.tv.settings.device.apps.AppManagementActivity")
                    data = Uri.parse("package:$packageName")
                },
                // 方案 E：Android TV 应用列表页
                Intent().apply {
                    component = ComponentName("com.android.tv.settings", "com.android.tv.settings.device.apps.AppsActivity")
                },
                // 方案 F：小米电视系统设置主页 (com.xiaomi.mitv.settings)
                Intent().apply {
                    component = ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.MainActivity")
                },
                // 方案 G：通用系统设置入口
                Intent(Settings.ACTION_SETTINGS)
            )

            var launched = false
            for (intent in candidateIntents) {
                try {
                    startActivity(intent)
                    launched = true
                    Toast.makeText(this, "请在设置中开启【允许显示在其他应用上层】权限", Toast.LENGTH_LONG).show()
                    break
                } catch (e: Exception) {
                    L.d("Failed to launch overlay intent candidate: $intent, error: ${e.message}")
                }
            }

            if (!launched) {
                showPermissionGuideDialog(
                    title = "悬浮窗权限配置引导",
                    message = "检测到当前电视系统精简了悬浮窗跳转接口。\n\n" +
                            "【配置方法】：\n" +
                            "1. 请在电视【设置】->【应用管理】->【XiaomiCoreService】中手动开启【允许显示在其他应用上层】。\n" +
                            "2. 或通过电脑执行以下 ADB 指令一键授权：\n\n" +
                            "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
                )
            }
        } else {
            Toast.makeText(this, "悬浮窗展示权限已成功开启", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 2. 安全跳转无障碍服务配置页（针对 Android TV / 小米电视 Leanback 深度适配多级降级机制）
     */
    private fun openAccessibilitySettings() {
        if (isFastDoubleClick()) return

        if (!TvAccessibilityService.isServiceRunning()) {
            // 核心安全前置：给家长临时放行 5 分钟无障碍拦截
            ParentAuthManager.grantTemporaryPass(5)

            val candidateIntents = listOf(
                // 方案 A：标准系统无障碍设置页
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                // 方案 B：小米电视系统设置主页 (com.xiaomi.mitv.settings)
                Intent().apply {
                    component = ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.MainActivity")
                },
                // 方案 C：Android TV 原生主设置页 (com.android.tv.settings)
                Intent().apply {
                    component = ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")
                },
                // 方案 D：通用系统设置入口
                Intent(Settings.ACTION_SETTINGS)
            )

            var launched = false
            for (intent in candidateIntents) {
                try {
                    startActivity(intent)
                    launched = true
                    Toast.makeText(this, "请在列表中找到【XiaomiCoreService】并开启服务", Toast.LENGTH_LONG).show()
                    break
                } catch (e: Exception) {
                    L.d("Failed to launch accessibility intent candidate: $intent, error: ${e.message}")
                }
            }

            if (!launched) {
                showPermissionGuideDialog(
                    title = "无障碍服务配置引导",
                    message = "检测到当前电视系统未提供无障碍直接配置页。\n\n" +
                            "【配置方法】：\n" +
                            "1. 请在电视【系统设置】查找【更多设置】或【辅助功能/无障碍】开启【XiaomiCoreService】。\n" +
                            "2. 或通过电脑连接电视执行以下 ADB 指令一键开启：\n\n" +
                            "adb shell settings put secure enabled_accessibility_services $packageName/com.parent.tvlock.service.TvAccessibilityService\n" +
                            "adb shell settings put secure accessibility_enabled 1"
                )
            }
        } else {
            Toast.makeText(this, "无障碍防逃逸服务运行正常", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 3. 安全激活设备管理器（针对 Android TV / 小米电视深度适配防崩机制）
     */
    private fun openDeviceAdminSettings() {
        if (isFastDoubleClick()) return

        if (!DeviceAdminManager.isAdminActive(this)) {
            // 核心安全前置：给家长临时放行 5 分钟无障碍拦截
            ParentAuthManager.grantTemporaryPass(5)

            val candidateIntents = listOf(
                // 方案 A：标准设备管理器激活界面
                DeviceAdminManager.buildEnableAdminIntent(this),
                // 方案 B：安全设置界面
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                // 方案 C：Android TV 原生安全设置页
                Intent().apply {
                    component = ComponentName("com.android.tv.settings", "com.android.tv.settings.system.SecurityActivity")
                },
                // 方案 D：小米电视设置主页
                Intent().apply {
                    component = ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.MainActivity")
                },
                // 方案 E：通用系统设置入口
                Intent(Settings.ACTION_SETTINGS)
            )

            var launched = false
            for (intent in candidateIntents) {
                try {
                    startActivity(intent)
                    launched = true
                    break
                } catch (e: Exception) {
                    L.d("Failed to launch device admin intent candidate: $intent, error: ${e.message}")
                }
            }

            if (!launched) {
                showPermissionGuideDialog(
                    title = "设备管理员配置引导",
                    message = "当前电视系统精简了设备管理员交互激活页。\n\n" +
                            "【配置建议】：\n" +
                            "可通过电脑连接电视执行以下 ADB 指令一键激活：\n\n" +
                            "adb shell dpm set-active-admin $packageName/com.parent.tvlock.receiver.TvDeviceAdminReceiver"
                )
            }
        } else {
            Toast.makeText(this, "设备管理员防卸载保护已生效", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 针对电视端精简 Intent 弹出的安全指引对话框
     */
    private fun showPermissionGuideDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun checkPermissionsAndUpdateUi() {
        // 悬浮窗
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        if (hasOverlay) {
            tvStatusOverlay.text = "已授权"
            tvStatusOverlay.setBackgroundResource(R.drawable.bg_ios_status_green)
            tvStatusOverlay.setTextColor(ContextCompat.getColor(this, R.color.ios_green))
        } else {
            tvStatusOverlay.text = "未授权"
            tvStatusOverlay.setBackgroundResource(R.drawable.bg_ios_status_red)
            tvStatusOverlay.setTextColor(ContextCompat.getColor(this, R.color.ios_red))
        }

        // 无障碍
        val hasAccessibility = TvAccessibilityService.isServiceRunning()
        if (hasAccessibility) {
            tvStatusAccessibility.text = "已生效"
            tvStatusAccessibility.setBackgroundResource(R.drawable.bg_ios_status_green)
            tvStatusAccessibility.setTextColor(ContextCompat.getColor(this, R.color.ios_green))
        } else {
            tvStatusAccessibility.text = "未开启"
            tvStatusAccessibility.setBackgroundResource(R.drawable.bg_ios_status_red)
            tvStatusAccessibility.setTextColor(ContextCompat.getColor(this, R.color.ios_red))
        }

        // 设备管理员
        val hasAdmin = DeviceAdminManager.isAdminActive(this)
        if (hasAdmin) {
            tvStatusDeviceAdmin.text = "已激活"
            tvStatusDeviceAdmin.setBackgroundResource(R.drawable.bg_ios_status_green)
            tvStatusDeviceAdmin.setTextColor(ContextCompat.getColor(this, R.color.ios_green))
        } else {
            tvStatusDeviceAdmin.text = "未激活"
            tvStatusDeviceAdmin.setBackgroundResource(R.drawable.bg_ios_status_red)
            tvStatusDeviceAdmin.setTextColor(ContextCompat.getColor(this, R.color.ios_red))
        }
    }

    private fun updateIntervalSelectionUi() {
        val currentMinutes = preferenceManager.lockIntervalMinutes
        tvCurrentIntervalText.text = "$currentMinutes 分钟"

        val ids = listOf(
            Pair(R.id.btnInterval5, 5),
            Pair(R.id.btnInterval10, 10),
            Pair(R.id.btnInterval15, 15),
            Pair(R.id.btnInterval30, 30),
            Pair(R.id.btnInterval60, 60)
        )
        for ((id, m) in ids) {
            val btn = findViewById<TextView>(id)
            if (m == currentMinutes) {
                btn.setBackgroundResource(R.drawable.bg_ios_segment_selected)
                btn.setTextColor(ContextCompat.getColor(this, R.color.white))
            } else {
                btn.setBackgroundResource(R.drawable.selector_ios_row)
                btn.setTextColor(ContextCompat.getColor(this, R.color.ios_text_secondary))
            }
        }
    }

    private fun performSync() {
        Toast.makeText(this, "正在从服务器同步最新题库配置...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = syncManager.syncQuestions()
            result.onSuccess { syncResult ->
                Toast.makeText(
                    this@MainActivity,
                    "同步成功！收到 ${syncResult.totalReceived} 题，入库 ${syncResult.savedCount} 题（图片按需免存直载，零占电视空间）",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { e ->
                Toast.makeText(this@MainActivity, "同步失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showConfigServerUrlDialog() {
        val input = EditText(this).apply {
            setText(preferenceManager.syncServerUrl)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("配置题库同步接口 URL")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotBlank()) {
                    preferenceManager.syncServerUrl = newUrl
                    tvCurrentServerUrl.text = newUrl
                    Toast.makeText(this, "接口地址已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showChangePasswordDialog() {
        val input = EditText(this).apply {
            hint = "请输入新的 6 位数字密码"
        }
        AlertDialog.Builder(this)
            .setTitle("修改家长密码")
            .setMessage("当前密码为：${preferenceManager.parentPassword}")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newPwd = input.text.toString().trim()
                if (newPwd.length == 6 && newPwd.all { it.isDigit() }) {
                    preferenceManager.parentPassword = newPwd
                    Toast.makeText(this, "家长密码修改成功！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "密码必须是 6 位纯数字", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshDashboardBriefStats() {
        val dbHelper = AppDatabaseHelper.getInstance(this)
        val answerDao = AnswerRecordDao(dbHelper)
        val wrongDao = WrongQuestionDao(dbHelper)
        val usageDao = AppUsageRecordDao(dbHelper)

        lifecycleScope.launch {
            val (stats, unmasteredCount, usageSeconds) = withContext(Dispatchers.IO) {
                val s = answerDao.getTodayStats()
                val w = wrongDao.getUnmasteredCount()
                val u = usageDao.getTodayTotalUsageSeconds()
                Triple(s, w, u)
            }

            val total = stats.first
            val correct = stats.second
            val accuracy = if (total > 0) (correct * 100 / total) else 0
            tvAnswerStatsBrief.text = "今日已答 $total 题 · 正确率 $accuracy%"
            tvWrongCountBrief.text = "待攻克错题：$unmasteredCount 道"
            tvUsageStatsBrief.text = "今日累计观看：${DateTimeUtils.formatDuration(usageSeconds)}"
        }
    }

    private fun registerCountdownReceiver() {
        countdownReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == LockForegroundService.ACTION_COUNTDOWN_TICK) {
                    val remaining = intent.getLongExtra(LockForegroundService.EXTRA_REMAINING_SECONDS, 0L)
                    val isLocked = intent.getBooleanExtra(LockForegroundService.EXTRA_IS_LOCKED, false)

                    if (isLocked) {
                        tvServiceStatus.text = "已锁定电视！请完成答题解锁"
                        tvServiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_red))
                    } else {
                        tvServiceStatus.text = "倒计时进行中 · 剩余 ${DateTimeUtils.formatDuration(remaining)}"
                        tvServiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_text_primary))
                    }
                }
            }
        }
        val filter = IntentFilter(LockForegroundService.ACTION_COUNTDOWN_TICK)
        ContextCompat.registerReceiver(
            this,
            countdownReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            countdownReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            L.w("Failed to unregister countdown receiver", tr = e)
        }
    }
}
