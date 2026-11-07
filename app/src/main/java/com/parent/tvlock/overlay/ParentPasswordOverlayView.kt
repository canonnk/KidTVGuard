package com.parent.tvlock.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.parent.tvlock.R
import com.parent.tvlock.util.FocusHelper
import com.parent.tvlock.util.L
import com.parent.tvlock.util.ParentAuthManager
import com.parent.tvlock.util.PreferenceManager

/**
 * 防逃逸家长 6 位密码全屏拦截悬浮窗
 */
class ParentPasswordOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnPasswordAuthListener {
        fun onAuthSuccess()
        fun onAuthCancelled()
    }

    private var authListener: OnPasswordAuthListener? = null
    private val preferenceManager = PreferenceManager.getInstance(context)

    private val tvPasswordTitle: TextView?
    private val tvPasswordSubtitle: TextView?
    private val tvPinDisplay: TextView
    private val tvPinError: TextView
    private val currentPin = StringBuilder()

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_parent_password_overlay, this, true)

        isFocusable = true
        isFocusableInTouchMode = true

        tvPasswordTitle = view.findViewById(R.id.tvPasswordTitle)
        tvPasswordSubtitle = view.findViewById(R.id.tvPasswordSubtitle)
        tvPinDisplay = view.findViewById(R.id.tvPinDisplay)
        tvPinError = view.findViewById(R.id.tvPinError)

        val keys = listOf(
            view.findViewById<TextView>(R.id.btnKey0),
            view.findViewById<TextView>(R.id.btnKey1),
            view.findViewById<TextView>(R.id.btnKey2),
            view.findViewById<TextView>(R.id.btnKey3),
            view.findViewById<TextView>(R.id.btnKey4),
            view.findViewById<TextView>(R.id.btnKey5),
            view.findViewById<TextView>(R.id.btnKey6),
            view.findViewById<TextView>(R.id.btnKey7),
            view.findViewById<TextView>(R.id.btnKey8),
            view.findViewById<TextView>(R.id.btnKey9),
            view.findViewById<TextView>(R.id.btnKeyDelete),
            view.findViewById<TextView>(R.id.btnKeyCancel)
        )

        // 遥控器焦点放大动效
        FocusHelper.setupFocusScaleBatch(*keys.toTypedArray())

        // 数字按键点击
        for (i in 0..9) {
            val keyView = view.findViewById<TextView>(
                resources.getIdentifier("btnKey$i", "id", context.packageName)
            )
            keyView?.setOnClickListener {
                appendDigit(i.toString())
            }
        }

        // 退格
        view.findViewById<View>(R.id.btnKeyDelete)?.setOnClickListener {
            if (currentPin.isNotEmpty()) {
                currentPin.deleteCharAt(currentPin.length - 1)
                updatePinDisplay()
            }
        }

        // 取消（返回电视桌面）
        view.findViewById<View>(R.id.btnKeyCancel)?.setOnClickListener {
            L.i("Parent password auth cancelled by user.")
            authListener?.onAuthCancelled()
        }

        // 默认将遥控器焦点定位在按键 1
        view.findViewById<View>(R.id.btnKey1)?.let {
            FocusHelper.requestFocusSafe(it)
        }
    }

    /**
     * 自定义标题与副标题文案（用于进入主程序鉴权与系统设置拦截等不同场景）
     */
    fun setCustomPrompt(title: String? = null, subtitle: String? = null) {
        title?.let { tvPasswordTitle?.text = it }
        subtitle?.let { tvPasswordSubtitle?.text = it }
    }

    /**
     * 重置已输入的 PIN 码并聚焦首个数字键
     */
    fun resetInput() {
        currentPin.clear()
        updatePinDisplay()
        focusFirstKey()
    }

    /**
     * 遥控器焦点快速定位至按键 1
     */
    fun focusFirstKey() {
        findViewById<View>(R.id.btnKey1)?.let {
            FocusHelper.requestFocusSafe(it)
        }
    }

    private fun appendDigit(digit: String) {
        if (currentPin.length < 6) {
            currentPin.append(digit)
            updatePinDisplay()

            if (currentPin.length == 6) {
                verifyPin()
            }
        }
    }

    private fun updatePinDisplay() {
        tvPinError.visibility = View.INVISIBLE
        val len = currentPin.length
        val masked = StringBuilder()
        for (i in 0 until 6) {
            if (i < len) {
                masked.append("● ")
            } else {
                masked.append("· ")
            }
        }
        tvPinDisplay.text = masked.toString().trim()
    }

    private fun verifyPin() {
        val enteredPin = currentPin.toString()
        val correctPassword = preferenceManager.parentPassword

        if (enteredPin == correctPassword) {
            L.i("Parent password verified successfully!")
            ParentAuthManager.grantTemporaryPass(5)
            authListener?.onAuthSuccess()
        } else {
            L.w("Incorrect parent password entered: $enteredPin")
            tvPinError.visibility = View.VISIBLE
            postDelayed({
                currentPin.clear()
                updatePinDisplay()
            }, 800L)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // 返回键作为取消处理，退回桌面（仅在 ACTION_UP 触发，避免 ACTION_DOWN / UP 重复回调）
            if (event.action == KeyEvent.ACTION_UP) {
                authListener?.onAuthCancelled()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun setOnPasswordAuthListener(listener: OnPasswordAuthListener) {
        this.authListener = listener
    }
}
