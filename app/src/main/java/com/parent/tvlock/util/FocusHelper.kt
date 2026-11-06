package com.parent.tvlock.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat

/**
 * Android TV 遥控器焦点放大与动效辅助类
 * 针对 小米电视4A 65英寸 大屏进行了边距防溢出与零裁切优化
 */
object FocusHelper {

    // 独立按钮与卡片的微缩放系数（控制在 1.025f，避免 65 寸大屏物理外扩过多导致撞边）
    private const val DEFAULT_BUTTON_SCALE = 1.025f
    private const val DEFAULT_DURATION = 150L

    /**
     * 为单个独立按键/卡片绑定获得焦点时的平滑适度缩放，并自动解除父容器裁切
     */
    fun setupFocusScale(
        view: View,
        scale: Float = DEFAULT_BUTTON_SCALE,
        duration: Long = DEFAULT_DURATION,
        onFocusChange: ((View, Boolean) -> Unit)? = null
    ) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        // 自动解除父容器与祖父容器的绘制裁切，防止放大边缘被切平
        unclipParents(view)

        view.setOnFocusChangeListener { v, hasFocus ->
            val targetScale = if (hasFocus) scale else 1.0f
            val targetZ = if (hasFocus) 10f else 2f

            v.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(duration)
                .start()

            ViewCompat.setElevation(v, targetZ)
            onFocusChange?.invoke(v, hasFocus)
        }
    }

    /**
     * 专为卡片内部列表行（Grouped Row）定制的获焦动效
     * 严禁大比例物理放大，保持 scale = 1.0f，仅激活内嵌发光圆角框与层级抬升，
     * 100% 确保焦点绝不超出卡片圆角边界
     */
    fun setupCardRowFocus(view: View, onFocusChange: ((View, Boolean) -> Unit)? = null) {
        setupFocusScale(view, scale = 1.0f, duration = 100L, onFocusChange = onFocusChange)
    }

    /**
     * 批量为卡片内嵌行绑定安全焦点
     */
    fun setupCardRowFocusBatch(vararg views: View) {
        for (view in views) {
            setupCardRowFocus(view)
        }
    }

    /**
     * 批量为一组独立视图绑定适度焦点缩放动效
     */
    fun setupFocusScaleBatch(vararg views: View) {
        for (view in views) {
            setupFocusScale(view)
        }
    }

    /**
     * 递归解除父级容器的裁切限制
     */
    private fun unclipParents(view: View) {
        view.post {
            var parent = view.parent
            var depth = 0
            while (parent is ViewGroup && depth < 3) {
                parent.clipChildren = false
                parent.clipToPadding = false
                parent = parent.parent
                depth++
            }
        }
    }

    /**
     * 安全请求焦点（通过 post 防止视图尚未完全 layout 导致抢焦失败）
     */
    fun requestFocusSafe(view: View) {
        view.post {
            view.requestFocus()
        }
    }
}
