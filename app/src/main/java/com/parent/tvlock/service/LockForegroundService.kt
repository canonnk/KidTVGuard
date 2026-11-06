package com.parent.tvlock.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.parent.tvlock.MainActivity
import com.parent.tvlock.R
import com.parent.tvlock.overlay.OverlayLockManager
import com.parent.tvlock.util.DateTimeUtils
import com.parent.tvlock.util.L
import com.parent.tvlock.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 电视防沉迷后台前台保活服务与倒计时中心
 */
class LockForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var countdownJob: Job? = null

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var overlayLockManager: OverlayLockManager
    private lateinit var notificationManager: NotificationManager

    // 当前倒计时剩余秒数
    private var remainingSeconds: Long = 0

    // 屏幕状态广播监听（亮屏断电死锁保障）
    private var screenReceiver: BroadcastReceiver? = null

    companion object {
        const val CHANNEL_ID = "tv_lock_foreground_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.parent.tvlock.action.START"
        const val ACTION_RESET_COUNTDOWN = "com.parent.tvlock.action.RESET_COUNTDOWN"
        const val ACTION_LOCK_NOW = "com.parent.tvlock.action.LOCK_NOW"
        const val ACTION_UNLOCK = "com.parent.tvlock.action.UNLOCK"

        // 倒计时广播（供 MainActivity 界面监听更新）
        const val ACTION_COUNTDOWN_TICK = "com.parent.tvlock.broadcast.COUNTDOWN_TICK"
        const val EXTRA_REMAINING_SECONDS = "extra_remaining_seconds"
        const val EXTRA_IS_LOCKED = "extra_is_locked"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, LockForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startCountdown(context: Context) {
            val intent = Intent(context, LockForegroundService::class.java).apply {
                action = ACTION_RESET_COUNTDOWN
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun lockImmediately(context: Context) {
            val intent = Intent(context, LockForegroundService::class.java).apply {
                action = ACTION_LOCK_NOW
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        L.i("LockForegroundService created.")
        preferenceManager = PreferenceManager.getInstance(this)
        overlayLockManager = OverlayLockManager.getInstance(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("电视守护服务运行中"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("电视守护服务运行中"))
        }

        registerScreenReceiver()

        // 启动时检查：如果处于锁定状态或目标时间已超时，直接锁定；否则恢复物理倒计时
        if (preferenceManager.isLocked) {
            L.i("Boot restored with isLocked = true, enforcing lock overlay immediately.")
            triggerLockScreen()
        } else {
            resumeOrStartCountdown()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_COUNTDOWN, ACTION_UNLOCK -> {
                L.i("Command received: Reset countdown.")
                preferenceManager.isLocked = false
                overlayLockManager.dismissLock()
                resetCountdown()
            }
            ACTION_LOCK_NOW -> {
                L.i("Command received: Lock now.")
                triggerLockScreen()
            }
            ACTION_START -> {
                if (preferenceManager.isLocked) {
                    triggerLockScreen()
                } else if (countdownJob == null || !countdownJob!!.isActive) {
                    resumeOrStartCountdown()
                }
            }
        }
        return START_STICKY
    }

    /**
     * 恢复或开启倒计时（基于物理绝对时间戳，防止杀后台或退出重置）
     */
    private fun resumeOrStartCountdown() {
        val targetTime = preferenceManager.lockTargetTimestamp
        val now = System.currentTimeMillis()
        if (targetTime > 0L) {
            val remaining = (targetTime - now) / 1000L
            if (remaining <= 0) {
                L.i("Physical lock target time already reached: target=$targetTime, now=$now. Enforcing lock!")
                triggerLockScreen()
                return
            } else {
                L.i("Resuming countdown from physical target timestamp: remaining $remaining seconds.")
                startCountdownLoop(remaining)
                return
            }
        }
        resetCountdown()
    }

    /**
     * 重置并开启全新周期的倒计时
     */
    private fun resetCountdown() {
        val totalMinutes = preferenceManager.lockIntervalMinutes
        val totalSeconds = totalMinutes * 60L
        preferenceManager.lockTargetTimestamp = System.currentTimeMillis() + totalSeconds * 1000L
        L.i("Starting new countdown with $totalMinutes minutes (target timestamp=${preferenceManager.lockTargetTimestamp}).")
        startCountdownLoop(totalSeconds)
    }

    /**
     * 核心倒计时轮询协程
     */
    private fun startCountdownLoop(initialSeconds: Long) {
        countdownJob?.cancel()
        remainingSeconds = initialSeconds
        countdownJob = serviceScope.launch {
            while (isActive && remainingSeconds > 0) {
                updateNotification("剩余观看时间：${DateTimeUtils.formatDuration(remainingSeconds)}")
                broadcastTick(remainingSeconds, isLocked = false)
                delay(1000L)
                remainingSeconds--
            }

            if (isActive && remainingSeconds <= 0) {
                L.i("Countdown reached zero. Triggering lock screen!")
                triggerLockScreen()
            }
        }
    }

    /**
     * 触发全屏锁死
     */
    private fun triggerLockScreen() {
        countdownJob?.cancel()
        remainingSeconds = 0
        preferenceManager.isLocked = true
        preferenceManager.lockTargetTimestamp = 0L
        overlayLockManager.showLock()
        updateNotification("电视观看时间已到，已锁定画面，请答题解锁")
        broadcastTick(0, isLocked = true)
    }

    /**
     * 向界面广播当前倒计时
     */
    private fun broadcastTick(seconds: Long, isLocked: Boolean) {
        val tickIntent = Intent(ACTION_COUNTDOWN_TICK).apply {
            putExtra(EXTRA_REMAINING_SECONDS, seconds)
            putExtra(EXTRA_IS_LOCKED, isLocked)
            setPackage(packageName)
        }
        sendBroadcast(tickIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "小米电视防沉迷保护服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "常驻后台监控电视使用时长并在超时后强制锁屏"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("小米电视防沉迷守护中")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 注册亮屏广播，确保关屏/开机断电再亮屏时死锁
     */
    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        L.i("Screen turned on / user present.")
                        if (preferenceManager.isLocked) {
                            L.w("Enforcing lock overlay upon SCREEN_ON.")
                            overlayLockManager.showLock()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        L.i("LockForegroundService destroying...")
        serviceScope.cancel()
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            L.w("Failed to unregister screen receiver", tr = e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        L.i("onTaskRemoved: Task swiped away. Ensuring LockForegroundService restarts.")
        try {
            val restartIntent = Intent(applicationContext, LockForegroundService::class.java).apply {
                action = ACTION_START
            }
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                1002,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 800L, pendingIntent)
        } catch (e: Exception) {
            L.w("Failed to schedule restart in onTaskRemoved", tr = e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
