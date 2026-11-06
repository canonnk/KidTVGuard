package com.parent.tvlock.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.parent.tvlock.service.LockForegroundService
import com.parent.tvlock.util.L
import com.parent.tvlock.util.PreferenceManager

/**
 * 全屏答题悬浮窗全局统筹管理器（单例）
 */
class OverlayLockManager private constructor(private val context: Context) {

    private val windowManager: WindowManager =
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val preferenceManager: PreferenceManager = PreferenceManager.getInstance(context)

    private var currentOverlayView: View? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    var isShowingLock: Boolean = false
        private set

    companion object {
        @Volatile
        private var instance: OverlayLockManager? = null

        fun getInstance(context: Context): OverlayLockManager {
            return instance ?: synchronized(this) {
                instance ?: OverlayLockManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 检查是否具备悬浮窗权限
     */
    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 展示锁屏悬浮窗（断电死锁与倒计时结束统一入口）
     */
    @Synchronized
    fun showLock() {
        if (isShowingLock && currentOverlayView != null) {
            L.d("Lock overlay is already showing.")
            return
        }

        if (!canDrawOverlays()) {
            L.w("Cannot show lock overlay: SYSTEM_ALERT_WINDOW permission not granted!")
            // 标记锁死状态，待用户授权或前台恢复时重新弹出
            preferenceManager.isLocked = true
            return
        }

        try {
            L.i("Showing TV lock overlay...")
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
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            // 在阶段3，先挂载一个全屏容器视图（阶段4会无缝替换为完整答题 QuestionOverlayView）
            val lockView = QuestionOverlayView(context)
            lockView.setOnAnswerResultListener(object : QuestionOverlayView.OnAnswerResultListener {
                override fun onAnswerCorrect() {
                    L.i("User answered correctly! Dismissing lock.")
                    dismissLock()
                    // 答对后重置并重新启动倒计时
                    LockForegroundService.startCountdown(context)
                }

                override fun onAnswerWrong() {
                    L.w("User answered wrong! Keeping TV locked.")
                }
            })

            windowManager.addView(lockView, layoutParams)
            currentOverlayView = lockView
            isShowingLock = true
            preferenceManager.isLocked = true
            L.i("Lock overlay successfully attached to WindowManager.")

            // 锁屏生效时：强制抢占音频焦点并发送媒体暂停事件，停止背景视频与音频播放
            pauseMediaPlayback()
        } catch (e: Exception) {
            L.e("Failed to attach lock overlay to WindowManager", tr = e)
            isShowingLock = false
        }
    }

    /**
     * 关闭并移除锁屏悬浮窗
     */
    @Synchronized
    fun dismissLock() {
        if (!isShowingLock && currentOverlayView == null) return

        try {
            currentOverlayView?.let {
                windowManager.removeView(it)
                L.i("Lock overlay removed from WindowManager.")
            }
        } catch (e: Exception) {
            L.e("Failed to remove lock overlay view", tr = e)
        } finally {
            currentOverlayView = null
            isShowingLock = false
            preferenceManager.isLocked = false
            // 解锁后释放音频焦点，允许用户继续观看
            resumeMediaPlayback()
        }
    }

    /**
     * 强行暂停当前电视播放的视频与背景声音
     */
    private fun pauseMediaPlayback() {
        try {
            // 1. 请求抢占瞬态音频焦点（AUDIOFOCUS_GAIN_TRANSIENT）
            // B站、抖音、爱奇艺、腾讯视频等正规播放器丢失音频焦点后，会自动立即暂停音视频
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }

            // 2. 兜底模拟分发系统全局媒体暂停按键事件 (KEYCODE_MEDIA_PAUSE)
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            L.i("Dispatched media pause key and seized audio focus.")
        } catch (e: Exception) {
            L.w("Failed to pause media playback", tr = e)
        }
    }

    /**
     * 解锁后释放音频焦点
     */
    private fun resumeMediaPlayback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            L.i("Released audio focus upon unlock.")
        } catch (e: Exception) {
            L.w("Failed to release audio focus", tr = e)
        }
    }
}
