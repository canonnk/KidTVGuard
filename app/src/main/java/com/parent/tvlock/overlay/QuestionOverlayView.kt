package com.parent.tvlock.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.parent.tvlock.R
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.dao.AnswerRecordDao
import com.parent.tvlock.db.dao.QuestionDao
import com.parent.tvlock.db.dao.WrongQuestionDao
import com.parent.tvlock.db.model.AnswerRecord
import com.parent.tvlock.db.model.Question
import com.parent.tvlock.net.BaiduSpeechManager
import com.parent.tvlock.net.MemoryImageLoader
import com.parent.tvlock.util.AudioRecorderHelper
import com.parent.tvlock.util.FocusHelper
import com.parent.tvlock.util.L
import com.parent.tvlock.util.ParentAuthManager
import com.parent.tvlock.util.ParentGateTrigger
import com.parent.tvlock.util.PenaltyManager
import com.parent.tvlock.util.PreferenceManager
import com.parent.tvlock.util.VoiceAnswerMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 电视横屏防沉迷答题全局全屏悬浮窗
 */
class QuestionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private var initialQuestionId: Long? = null
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnAnswerResultListener {
        fun onAnswerCorrect()
        fun onAnswerWrong()
    }

    companion object {
        private val ESCAPE_KEY_CODES = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_TV,
            KeyEvent.KEYCODE_TV_INPUT
        )

        /**
         * 智能检查当前环境是否满足运行语音认读题的所有必要条件
         * 必须同时满足：
         * 1. 麦克风 RECORD_AUDIO 权限已授予；
         * 2. 网络已连接（百度语音识别是云端接口，断网无法识别）；
         * 3. 百度语音 API Key 与 Secret Key 已有效配置（非空且非出厂默认占位符）。
         */
        fun isVoiceQuestionEligible(context: Context): Boolean {
            // 1. 录音权限检查
            val hasMicPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMicPermission) {
                L.d("Voice question not eligible: RECORD_AUDIO permission not granted.")
                return false
            }

            // 2. 网络连通性检查
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isNetworkConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm?.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm?.activeNetworkInfo?.isConnected == true
            }
            if (!isNetworkConnected) {
                L.d("Voice question not eligible: Device is offline.")
                return false
            }

            // 3. 百度云语音鉴权 Key 有效性检查（排除默认测试占位符）
            val pref = PreferenceManager.getInstance(context)
            val apiKey = pref.baiduAsrApiKey.trim()
            val secretKey = pref.baiduAsrSecretKey.trim()
            if (apiKey.isBlank() || secretKey.isBlank()) {
                L.d("Voice question not eligible: Baidu API Key or Secret Key is blank.")
                return false
            }
            if (apiKey == PreferenceManager.DEFAULT_BAIDU_ASR_API_KEY || secretKey == PreferenceManager.DEFAULT_BAIDU_ASR_SECRET_KEY) {
                L.d("Voice question not eligible: Baidu API Key is placeholder dummy key.")
                return false
            }

            return true
        }
    }

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var countdownNextJob: Job? = null

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val questionDao = QuestionDao(dbHelper)
    private val wrongQuestionDao = WrongQuestionDao(dbHelper)
    private val answerRecordDao = AnswerRecordDao(dbHelper)
    private val preferenceManager = PreferenceManager.getInstance(context)
    private val penaltyManager = PenaltyManager.getInstance(context)
    private val baiduSpeechManager = BaiduSpeechManager.getInstance(context)
    private val audioRecorder = AudioRecorderHelper(viewScope)

    private var answerResultListener: OnAnswerResultListener? = null

    // UI 控件 - 基础
    private val tvSubjectBadge: TextView
    private val tvQuestionProgress: TextView
    private val tvQuestionText: TextView
    private val ivQuestionImage: ImageView

    // UI 控件 - 选择题 (Choice)
    private val layoutChoiceContainer: LinearLayout
    private val btnOptionA: View
    private val btnOptionB: View
    private val btnOptionC: View
    private val btnOptionD: View

    private val tvOptionTagA: TextView
    private val tvOptionTagB: TextView
    private val tvOptionTagC: TextView
    private val tvOptionTagD: TextView

    private val ivOptionA: ImageView
    private val ivOptionB: ImageView
    private val ivOptionC: ImageView
    private val ivOptionD: ImageView

    private val tvOptionA: TextView
    private val tvOptionB: TextView
    private val tvOptionC: TextView
    private val tvOptionD: TextView

    // UI 控件 - 语音题 (Voice)
    private val cardVoiceSquareImage: View
    private val ivVoiceSquareImage: ImageView
    private val layoutVoiceContainer: LinearLayout
    private val btnVoiceRecord: View
    private val ivMicIcon: ImageView
    private val tvVoiceStatus: TextView
    private val tvVoiceCountdown: TextView
    private val layoutVoiceFeedback: LinearLayout
    private val pbVoiceLoading: ProgressBar
    private val tvVoiceResult: TextView

    // UI 控件 - 反馈
    private val layoutExplanation: LinearLayout
    private val tvFeedbackTitle: TextView
    private val tvExplanation: TextView
    private val tvCountdownNotice: TextView

    private val layoutSuccess: LinearLayout
    private val tvSuccessNotice: TextView

    // UI 控件 - 惩罚面板
    private val cardQuestion: View
    private val layoutPenalty: LinearLayout
    private val tvPenaltyTitle: TextView
    private val tvPenaltyCountdown: TextView
    private val tvPenaltyHint: TextView

    // 运行态数据
    private var currentQuestion: Question? = null
    private var questionStartTime: Long = 0L
    private var isSubmitting: Boolean = false
    private var isVoiceRecording: Boolean = false

    // 答错惩罚机制运行态
    private var attemptsUsed: Int = 0              // 当前题已用作答次数
    private var currentIsPractice: Boolean = false // 错题本强化重练：不触发惩罚、不持久化作答进度
    private var progressBaseLabel: String = ""
    private var isInPenalty: Boolean = false
    private var penaltyJob: Job? = null

    // 家长应急入口（连按菜单键 -> 家长密码 -> 操作面板）
    private var parentGateView: View? = null
    private var contentRoot: View? = null

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_question_overlay, this, true)
        contentRoot = getChildAt(0)

        isFocusable = true
        isFocusableInTouchMode = true

        tvSubjectBadge = view.findViewById(R.id.tvSubjectBadge)
        tvQuestionProgress = view.findViewById(R.id.tvQuestionProgress)
        tvQuestionText = view.findViewById(R.id.tvQuestionText)
        ivQuestionImage = view.findViewById(R.id.ivQuestionImage)

        layoutChoiceContainer = view.findViewById(R.id.layoutChoiceContainer)
        btnOptionA = view.findViewById(R.id.btnOptionA)
        btnOptionB = view.findViewById(R.id.btnOptionB)
        btnOptionC = view.findViewById(R.id.btnOptionC)
        btnOptionD = view.findViewById(R.id.btnOptionD)

        tvOptionTagA = view.findViewById(R.id.tvOptionTagA)
        tvOptionTagB = view.findViewById(R.id.tvOptionTagB)
        tvOptionTagC = view.findViewById(R.id.tvOptionTagC)
        tvOptionTagD = view.findViewById(R.id.tvOptionTagD)

        ivOptionA = view.findViewById(R.id.ivOptionA)
        ivOptionB = view.findViewById(R.id.ivOptionB)
        ivOptionC = view.findViewById(R.id.ivOptionC)
        ivOptionD = view.findViewById(R.id.ivOptionD)

        tvOptionA = view.findViewById(R.id.tvOptionA)
        tvOptionB = view.findViewById(R.id.tvOptionB)
        tvOptionC = view.findViewById(R.id.tvOptionC)
        tvOptionD = view.findViewById(R.id.tvOptionD)

        // 语音题专属视图绑定
        cardVoiceSquareImage = view.findViewById(R.id.cardVoiceSquareImage)
        ivVoiceSquareImage = view.findViewById(R.id.ivVoiceSquareImage)
        layoutVoiceContainer = view.findViewById(R.id.layoutVoiceContainer)
        btnVoiceRecord = view.findViewById(R.id.btnVoiceRecord)
        ivMicIcon = view.findViewById(R.id.ivMicIcon)
        tvVoiceStatus = view.findViewById(R.id.tvVoiceStatus)
        tvVoiceCountdown = view.findViewById(R.id.tvVoiceCountdown)
        layoutVoiceFeedback = view.findViewById(R.id.layoutVoiceFeedback)
        pbVoiceLoading = view.findViewById(R.id.pbVoiceLoading)
        tvVoiceResult = view.findViewById(R.id.tvVoiceResult)

        layoutExplanation = view.findViewById(R.id.layoutExplanation)
        tvFeedbackTitle = view.findViewById(R.id.tvFeedbackTitle)
        tvExplanation = view.findViewById(R.id.tvExplanation)
        tvCountdownNotice = view.findViewById(R.id.tvCountdownNotice)

        layoutSuccess = view.findViewById(R.id.layoutSuccess)
        tvSuccessNotice = view.findViewById(R.id.tvSuccessNotice)

        cardQuestion = view.findViewById(R.id.cardQuestion)
        layoutPenalty = view.findViewById(R.id.layoutPenalty)
        tvPenaltyTitle = view.findViewById(R.id.tvPenaltyTitle)
        tvPenaltyCountdown = view.findViewById(R.id.tvPenaltyCountdown)
        tvPenaltyHint = view.findViewById(R.id.tvPenaltyHint)

        // 遥控器焦点放大与缩放动效
        FocusHelper.setupFocusScaleBatch(btnOptionA, btnOptionB, btnOptionC, btnOptionD, btnVoiceRecord)

        // 选择题点击与遥控器 OK 键事件绑定
        btnOptionA.setOnClickListener { handleAnswerSubmission("A") }
        btnOptionB.setOnClickListener { handleAnswerSubmission("B") }
        btnOptionC.setOnClickListener { handleAnswerSubmission("C") }
        btnOptionD.setOnClickListener { handleAnswerSubmission("D") }

        // 语音题触屏长按/点击兼容处理（在支持触屏或鼠标的设备上同样可用）
        setupVoiceRecordButtonTouch()

        // 根据家长设置的防沉迷锁屏周期，动态显示副标题文案
        val tvLockSubtitle = view.findViewById<TextView>(R.id.tvLockSubtitle)
        val interval = preferenceManager.lockIntervalMinutes
        val penaltyMinutes = preferenceManager.penaltyLockMinutes
        tvLockSubtitle?.text = if (penaltyMinutes > 0) {
            "答对本题即可重新解锁电视 $interval 分钟 · 答错次数用完将锁定 $penaltyMinutes 分钟"
        } else {
            "答对本题即可重新解锁电视 $interval 分钟 · 答错将展示详细解析并自动换题"
        }

        // 若仍处于惩罚锁定中（如断电/重启/重新亮屏），直接显示惩罚倒计时；否则载入题目
        if (penaltyManager.isActive()) {
            L.i("Penalty still active on overlay creation, showing penalty panel.")
            showPenaltyPanel(keepQuestionVisible = false)
        } else {
            // 惩罚已到期（含关机期间到期）：清理残留状态，出新题
            penaltyManager.clearPenalty()
            loadNextQuestion()
        }
    }

    /**
     * 兼容触摸屏/鼠标设备的长按与点击
     */
    private fun setupVoiceRecordButtonTouch() {
        btnVoiceRecord.setOnTouchListener { _, event ->
            if (currentQuestion?.isVoiceType != true || isSubmitting) return@setOnTouchListener false
            val mode = preferenceManager.voiceInteractionMode
            if (mode == PreferenceManager.VOICE_MODE_PUSH_TO_TALK) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!isVoiceRecording) startVoiceRecording()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isVoiceRecording) stopVoiceRecording()
                        true
                    }
                    else -> false
                }
            } else {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (!isVoiceRecording) startVoiceRecording() else stopVoiceRecording()
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * 遥控器按键全局拦截：严防返回键、菜单键、设置键等逃逸，并支持遥控器语音键/OK键触发语音录制
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 家长验证/操作面板展示期间：仅放行面板自身按键，逃逸键依旧拦截，且不触发语音录制
        if (parentGateView != null) {
            if (ESCAPE_KEY_CODES.contains(event.keyCode)) return true
            return super.dispatchKeyEvent(event)
        }

        // 连按菜单键 5 次唤起家长应急入口
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (ParentGateTrigger.onMenuDown()) {
                showParentGate()
                return true
            }
        }

        if (ESCAPE_KEY_CODES.contains(event.keyCode)) {
            L.d("Escape key intercepted in QuestionOverlayView: ${event.keyCode}")
            return true // 完全消耗逃逸按键事件，禁止呼出菜单或退出
        }

        // 语音题专属按键响应（支持标准蓝牙语音键 KEYCODE_VOICE_ASSIST/KEYCODE_SEARCH 及 确定/OK键）
        val isVoiceQuestion = currentQuestion?.isVoiceType == true
        val isVoiceTriggerKey = event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
                event.keyCode == KeyEvent.KEYCODE_SEARCH ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER

        if (isVoiceQuestion && isVoiceTriggerKey && !isSubmitting) {
            val mode = preferenceManager.voiceInteractionMode
            if (mode == PreferenceManager.VOICE_MODE_PUSH_TO_TALK) {
                // 对讲机模式：按住说话，松开提交
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0 && !isVoiceRecording) {
                        startVoiceRecording()
                    }
                    return true
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (isVoiceRecording) {
                        stopVoiceRecording()
                    }
                    return true
                }
            } else {
                // 单击模式：按一下开始，再按一下或超时自动提交
                if (event.action == KeyEvent.ACTION_UP) {
                    if (!isVoiceRecording) {
                        startVoiceRecording()
                    } else {
                        stopVoiceRecording()
                    }
                    return true
                }
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // ======================= 家长应急入口 =======================

    /**
     * 展示家长密码验证；验证通过后可选择“清除惩罚并换新题”或“直接解锁电视”
     */
    fun showParentGate() {
        if (parentGateView != null) return
        L.i("Parent gate requested on lock overlay.")

        if (isVoiceRecording) {
            audioRecorder.stopRecording()
            isVoiceRecording = false
        }

        val gate = ParentPasswordOverlayView(context)
        gate.setCustomPrompt(
            title = "🔒 家长验证",
            subtitle = "请输入 6 位家长密码，可清除惩罚或直接解锁"
        )
        gate.setOnPasswordAuthListener(object : ParentPasswordOverlayView.OnPasswordAuthListener {
            override fun onAuthSuccess() {
                // 仅用于本次操作，不保留系统设置的临时放行
                ParentAuthManager.revokePass()
                showParentActions()
            }

            override fun onAuthCancelled() {
                dismissParentGate()
            }
        })
        attachParentGateView(gate)
        gate.post { gate.resetInput() }
    }

    private fun showParentActions() {
        val panel = LayoutInflater.from(context).inflate(R.layout.view_parent_action_overlay, this, false)
        val btnReset = panel.findViewById<View>(R.id.btnParentResetPenalty)
        val btnUnlock = panel.findViewById<View>(R.id.btnParentUnlock)
        val btnCancel = panel.findViewById<View>(R.id.btnParentActionCancel)
        val tvStatus = panel.findViewById<TextView>(R.id.tvParentActionStatus)

        val penalty = penaltyManager.status()
        tvStatus.text = if (penalty.active) {
            "当前处于答错惩罚中，剩余 ${formatMmSs(penalty.remainingSeconds)}"
        } else {
            "当前处于答题锁定中"
        }

        FocusHelper.setupCardRowFocusBatch(btnReset, btnUnlock, btnCancel)

        btnReset.setOnClickListener {
            L.i("Parent cleared penalty from lock overlay.")
            dismissParentGate()
            penaltyJob?.cancel()
            onPenaltyFinished()
        }
        btnUnlock.setOnClickListener {
            L.i("Parent unlocked TV from lock overlay.")
            dismissParentGate()
            answerResultListener?.onAnswerCorrect()
        }
        btnCancel.setOnClickListener { dismissParentGate() }

        // 替换密码面板为操作面板
        parentGateView?.let { removeView(it) }
        attachParentGateView(panel)
        panel.post { FocusHelper.requestFocusSafe(btnReset) }
    }

    private fun attachParentGateView(gate: View) {
        if (parentGateView == null) {
            // 阻止底层答题控件被方向键抢走焦点
            (contentRoot as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        parentGateView = gate
        addView(gate, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gate.requestFocus()
    }

    private fun dismissParentGate() {
        parentGateView?.let { removeView(it) }
        parentGateView = null
        (contentRoot as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        ParentGateTrigger.reset()
        // 恢复答题界面焦点
        when {
            isInPenalty -> Unit
            currentQuestion?.isVoiceType == true -> FocusHelper.requestFocusSafe(btnVoiceRecord)
            else -> listOf(btnOptionA, btnOptionB, btnOptionC, btnOptionD)
                .firstOrNull { it.isEnabled }
                ?.let { FocusHelper.requestFocusSafe(it) }
        }
    }

    fun setOnAnswerResultListener(listener: OnAnswerResultListener) {
        this.answerResultListener = listener
    }

    private fun isVoiceQuestionEligible(): Boolean {
        return isVoiceQuestionEligible(context)
    }

    /** 抽题结果：题目 + 来源标记 */
    private data class PickedQuestion(
        val question: Question?,
        val fromWrongBook: Boolean,
        val practice: Boolean = false,
        val restored: Boolean = false
    )

    /**
     * 抽取并加载下一道题目
     * @param forceChoiceOnly 是否强制仅抽选择题（在语音启动失败或降级时使用）
     */
    private fun loadNextQuestion(forceChoiceOnly: Boolean = false) {
        countdownNextJob?.cancel()
        isSubmitting = false
        isInPenalty = false

        // 重置选项背景与状态
        resetOptionsUi()

        viewScope.launch {
            val isVoiceEligible = !forceChoiceOnly && isVoiceQuestionEligible()

            val picked = withContext(Dispatchers.IO) {
                // 若指定了初始重练题目（如从错题本点击进入），优先加载该指定题（需同时满足语音题环境可用性）
                initialQuestionId?.let { designatedId ->
                    initialQuestionId = null
                    val designated = questionDao.getQuestionById(designatedId)
                    if (designated != null && (isVoiceEligible || !designated.isVoiceType)) {
                        return@withContext PickedQuestion(designated, fromWrongBook = true, practice = true)
                    }
                }

                // 重启/重新亮屏后沿用未答完的题目与已用次数，防止靠重启刷新作答次数或刷题
                val pendingId = penaltyManager.pendingQuestionId()
                if (pendingId > 0L) {
                    val pending = questionDao.getQuestionById(pendingId)
                    if (pending != null && (isVoiceEligible || !pending.isVoiceType)) {
                        return@withContext PickedQuestion(pending, fromWrongBook = false, restored = true)
                    }
                    // 题目已不存在或当前环境无法作答该题型：丢弃旧进度
                    penaltyManager.clearAttempts()
                }

                // 优先从错题本中抽取待巩固的错题
                val wrongQuestionId = wrongQuestionDao.getRandomUnmasteredQuestionId()
                if (wrongQuestionId != null && Math.random() < 0.5) {
                    val q = questionDao.getQuestionById(wrongQuestionId)
                    if (q != null && (isVoiceEligible || !q.isVoiceType)) {
                        return@withContext PickedQuestion(q, fromWrongBook = true)
                    }
                }

                // 若无错题或按概率走全库抽取未答对题（若环境不具备语音能力则严格过滤为选择题）
                val randomQ = questionDao.getRandomQuestionForLock(onlyChoice = !isVoiceEligible)
                PickedQuestion(randomQ, fromWrongBook = false)
            }

            val targetQ = picked.question

            if (targetQ == null) {
                L.w("No question available in database!")
                tvQuestionText.text = "暂无可用题目，请确保已同步题库。"
                return@launch
            }

            currentQuestion = targetQ
            currentIsPractice = picked.practice
            questionStartTime = System.currentTimeMillis()

            // 初始化本题作答次数（非练习模式下可从持久化进度恢复；且至少保留 1 次机会）
            val maxAttempts = preferenceManager.maxAttemptsFor(targetQ.questionType)
            attemptsUsed = if (!picked.practice) {
                penaltyManager.attemptsUsed(targetQ.id).coerceIn(0, maxAttempts - 1)
            } else {
                0
            }

            bindQuestionToUi(targetQ, picked.fromWrongBook, picked.restored)
        }
    }

    /** 当前题剩余作答机会 */
    private fun attemptsLeft(question: Question): Int {
        return (preferenceManager.maxAttemptsFor(question.questionType) - attemptsUsed).coerceAtLeast(0)
    }

    /** 刷新题目来源标签 + 剩余机会提示 */
    private fun updateProgressLabel(question: Question) {
        val left = attemptsLeft(question)
        tvQuestionProgress.text = "$progressBaseLabel · 本题还有 $left 次机会"
    }

    /**
     * 登记一次答错/未匹配。返回 true 表示还有剩余机会可重答；false 表示机会已用完。
     */
    private fun registerWrongAttempt(question: Question): Boolean {
        attemptsUsed++
        if (!currentIsPractice) {
            penaltyManager.saveAttempts(question.id, attemptsUsed)
        }
        return attemptsLeft(question) > 0
    }

    /**
     * 作答机会用完后的处理：清除作答进度，并按配置开启惩罚锁定（同步落盘）。
     * @return 是否已开启惩罚锁定（练习模式或时长为 0 时不惩罚）
     */
    private fun onAttemptsExhausted(): Boolean {
        val minutes = preferenceManager.penaltyLockMinutes
        val penalize = !currentIsPractice && minutes > 0
        if (penalize) {
            penaltyManager.start(minutes * 60_000L)
        }
        if (!currentIsPractice) {
            penaltyManager.clearAttempts()
        }
        return penalize
    }

    /**
     * 答对后清除作答进度
     */
    private fun onAnsweredCorrectly() {
        attemptsUsed = 0
        if (!currentIsPractice) {
            penaltyManager.clearAttempts()
        }
    }

    // ======================= 惩罚等待面板 =======================

    /**
     * 显示惩罚等待面板并启动倒计时
     * @param keepQuestionVisible true: 保留刚答错的题目与解析（现场进入惩罚）；false: 隐藏题目卡片（重启/亮屏后恢复）
     */
    private fun showPenaltyPanel(keepQuestionVisible: Boolean) {
        isInPenalty = true
        isSubmitting = true
        countdownNextJob?.cancel()
        penaltyJob?.cancel()

        if (isVoiceRecording) {
            audioRecorder.stopRecording()
            isVoiceRecording = false
        }

        cardQuestion.visibility = if (keepQuestionVisible) View.VISIBLE else View.GONE
        layoutSuccess.visibility = View.GONE
        if (!keepQuestionVisible) layoutExplanation.visibility = View.GONE
        tvCountdownNotice.visibility = View.GONE
        btnVoiceRecord.isEnabled = false
        layoutPenalty.visibility = View.VISIBLE

        penaltyJob = viewScope.launch {
            // 每秒轮询真实时间，天然兼容开机后 NTP 校时导致的时间跳变
            while (true) {
                val status = penaltyManager.status()
                if (!status.active) break

                tvPenaltyCountdown.text = formatMmSs(status.remainingSeconds)
                tvPenaltyHint.text = if (status.clockUnsynced) {
                    "正在等待系统时间校准，请确保电视已联网..."
                } else {
                    "倒计时结束后将自动出新题，答对即可解锁电视"
                }
                delay(1000L)
            }
            onPenaltyFinished()
        }
    }

    private fun onPenaltyFinished() {
        L.i("Penalty finished, loading a new question.")
        penaltyManager.clearAll()
        layoutPenalty.visibility = View.GONE
        layoutExplanation.visibility = View.GONE
        cardQuestion.visibility = View.VISIBLE
        currentQuestion = null
        loadNextQuestion()
    }

    private fun formatMmSs(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    /**
     * 将题目数据渲染到界面视图
     */
    private fun bindQuestionToUi(question: Question, isFromWrongBook: Boolean, isRestored: Boolean = false) {
        // 科目标签（采用 Apple 语义化活力色彩）
        tvSubjectBadge.text = question.subject
        val badgeColor = when (question.subject) {
            "数学" -> ContextCompat.getColor(context, R.color.ios_blue)
            "语文" -> ContextCompat.getColor(context, R.color.ios_red)
            "英语" -> ContextCompat.getColor(context, R.color.ios_green)
            "物理" -> ContextCompat.getColor(context, R.color.ios_purple)
            else -> ContextCompat.getColor(context, R.color.ios_orange)
        }
        tvSubjectBadge.background?.setTint(badgeColor)

        progressBaseLabel = when {
            isRestored -> "继续作答"
            isFromWrongBook -> "错题重练巩固"
            else -> "防沉迷随机抽题"
        }
        updateProgressLabel(question)
        tvQuestionText.text = question.questionText

        if (question.isVoiceType) {
            // ================= 幼儿看图语音认读题 =================
            layoutChoiceContainer.visibility = View.GONE
            ivQuestionImage.visibility = View.GONE
            cardVoiceSquareImage.visibility = View.VISIBLE
            layoutVoiceContainer.visibility = View.VISIBLE

            // 加载中央大正方形图案（纯内存按需流式解码，零磁盘占用）
            val squareImgUrl = question.questionImageUrl
            if (!squareImgUrl.isNullOrBlank()) {
                MemoryImageLoader.getInstance(context).loadImage(
                    scope = viewScope,
                    url = squareImgUrl,
                    targetView = ivVoiceSquareImage,
                    reqWidth = 600,
                    reqHeight = 600
                )
            } else {
                ivVoiceSquareImage.setImageDrawable(null)
            }

            // 重置语音交互状态
            resetVoiceUi()

            // 焦点自动定位于语音录制主按钮
            FocusHelper.requestFocusSafe(btnVoiceRecord)
        } else {
            // ================= 传统选择题（含方案2看图选图） =================
            cardVoiceSquareImage.visibility = View.GONE
            layoutVoiceContainer.visibility = View.GONE
            layoutChoiceContainer.visibility = View.VISIBLE

            // 题干图片渲染（优先从网络 URL 纯内存流式解码加载，零磁盘占用）
            val stemUrl = question.questionImageUrl
            if (!stemUrl.isNullOrBlank()) {
                MemoryImageLoader.getInstance(context).loadImage(
                    scope = viewScope,
                    url = stemUrl,
                    targetView = ivQuestionImage,
                    reqWidth = 800,
                    reqHeight = 400
                )
            } else if (!question.localImagePath.isNullOrBlank() && File(question.localImagePath).exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(question.localImagePath)
                    if (bitmap != null) {
                        ivQuestionImage.setImageBitmap(bitmap)
                        ivQuestionImage.visibility = View.VISIBLE
                    } else {
                        ivQuestionImage.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    L.e("Failed to decode image from local path: ${question.localImagePath}", tr = e)
                    ivQuestionImage.visibility = View.GONE
                }
            } else {
                ivQuestionImage.setImageDrawable(null)
                ivQuestionImage.visibility = View.GONE
            }

            // 选项渲染（支持方案2：选项带图，纯内存按需下载渲染，保持苹果级居中布局）
            bindOptionItem(btnOptionA, tvOptionTagA, "A", ivOptionA, question.optionAImageUrl, tvOptionA, question.optionA)
            bindOptionItem(btnOptionB, tvOptionTagB, "B", ivOptionB, question.optionBImageUrl, tvOptionB, question.optionB)
            bindOptionItem(btnOptionC, tvOptionTagC, "C", ivOptionC, question.optionCImageUrl, tvOptionC, question.optionC)
            bindOptionItem(btnOptionD, tvOptionTagD, "D", ivOptionD, question.optionDImageUrl, tvOptionD, question.optionD)

            // 强制使选项 A 抢占电视遥控器初始焦点
            FocusHelper.requestFocusSafe(btnOptionA)
        }
    }

    /**
     * 单个选项卡片数据绑定（支持纯文本题、图文混排题、纯图片选图题）
     */
    private fun bindOptionItem(
        container: View,
        tvTag: TextView,
        tag: String,
        ivOption: ImageView,
        imageUrl: String?,
        tvText: TextView,
        rawText: String
    ) {
        tvTag.text = tag

        // 1. 选项图片：纯内存流式按需加载，零磁盘存储
        if (!imageUrl.isNullOrBlank()) {
            MemoryImageLoader.getInstance(context).loadImage(
                scope = viewScope,
                url = imageUrl,
                targetView = ivOption,
                reqWidth = 240,
                reqHeight = 160
            )
        } else {
            ivOption.setImageDrawable(null)
            ivOption.visibility = View.GONE
        }

        // 2. 选项文字：清洗可能重复的 "A." 前缀
        val cleanText = rawText.trim()
            .removePrefix("$tag.")
            .removePrefix("$tag、")
            .removePrefix("$tag: ")
            .removePrefix("$tag ")
            .trim()

        if (cleanText.isEmpty() || cleanText.equals(tag, ignoreCase = true)) {
            // 看图选图题，若选项文本为空或仅为标签自身
            if (!imageUrl.isNullOrBlank()) {
                tvText.visibility = View.GONE
            } else {
                tvText.visibility = View.VISIBLE
                tvText.text = tag
            }
        } else {
            tvText.visibility = View.VISIBLE
            tvText.text = cleanText
        }
    }

    /**
     * 重置选项视图状态
     */
    private fun resetOptionsUi() {
        val normalDrawable = R.drawable.selector_option_button
        btnOptionA.setBackgroundResource(normalDrawable)
        btnOptionB.setBackgroundResource(normalDrawable)
        btnOptionC.setBackgroundResource(normalDrawable)
        btnOptionD.setBackgroundResource(normalDrawable)

        btnOptionA.isEnabled = true
        btnOptionB.isEnabled = true
        btnOptionC.isEnabled = true
        btnOptionD.isEnabled = true

        layoutExplanation.visibility = View.GONE
        tvExplanation.visibility = View.VISIBLE
        tvCountdownNotice.visibility = View.VISIBLE
        layoutSuccess.visibility = View.GONE
        btnVoiceRecord.isEnabled = true
    }

    /**
     * 重置语音答题视图状态
     */
    private fun resetVoiceUi() {
        btnVoiceRecord.setBackgroundResource(R.drawable.selector_option_button)
        btnVoiceRecord.isEnabled = true
        tvVoiceStatus.text = getVoicePromptText()
        tvVoiceCountdown.visibility = View.GONE
        tvVoiceCountdown.text = ""
        pbVoiceLoading.visibility = View.GONE
        tvVoiceResult.text = ""
        layoutVoiceFeedback.visibility = View.VISIBLE
    }

    private fun getVoicePromptText(): String {
        return if (preferenceManager.voiceInteractionMode == PreferenceManager.VOICE_MODE_PUSH_TO_TALK) {
            "按住遥控器语音键 或 按住【确定】键说话"
        } else {
            "按遥控器语音键 或 按【确定】键开始说话"
        }
    }

    /**
     * 启动语音录制（纯内存流转，静音电视）
     */
    private fun startVoiceRecording() {
        if (isVoiceRecording || isSubmitting) return
        isVoiceRecording = true

        btnVoiceRecord.setBackgroundResource(R.drawable.bg_option_btn_wrong)
        tvVoiceStatus.text = "🔴 正在聆听中... 请大声说出答案"
        tvVoiceCountdown.visibility = View.VISIBLE
        pbVoiceLoading.visibility = View.GONE
        tvVoiceResult.text = "正在收音..."

        val maxDuration = preferenceManager.voiceMaxDurationSeconds
        countdownNextJob?.cancel()
        countdownNextJob = viewScope.launch {
            for (sec in maxDuration downTo 1) {
                tvVoiceCountdown.text = "(${sec}s)"
                delay(1000L)
            }
        }

        val started = audioRecorder.startRecording(
            maxDurationSeconds = maxDuration,
            onAmplitude = { ratio ->
                if (isVoiceRecording) {
                    val bars = "▌".repeat((ratio * 8).toInt().coerceIn(1, 8))
                    tvVoiceResult.text = "正在收音音量: $bars"
                }
            },
            onFinished = { pcmBytes ->
                onVoiceRecordingCompleted(pcmBytes)
            }
        )

        if (!started) {
            isVoiceRecording = false
            countdownNextJob?.cancel()
            resetVoiceUi()
            tvVoiceResult.text = "未检测到可用麦克风，正在为您切换为选择题..."
            viewScope.launch {
                delay(1500L)
                loadNextQuestion(forceChoiceOnly = true)
            }
        }
    }

    /**
     * 停止语音录制
     */
    private fun stopVoiceRecording() {
        if (!isVoiceRecording) return
        countdownNextJob?.cancel()
        audioRecorder.stopRecording()
    }

    /**
     * 录音结束，送往百度 ASR 极速版识别（纯内存 Base64，零磁盘）
     */
    private fun onVoiceRecordingCompleted(pcmBytes: ByteArray?) {
        isVoiceRecording = false
        countdownNextJob?.cancel()
        tvVoiceCountdown.visibility = View.GONE

        btnVoiceRecord.setBackgroundResource(R.drawable.selector_option_button)

        if (pcmBytes == null || pcmBytes.size < 3200) {
            tvVoiceStatus.text = getVoicePromptText()
            tvVoiceResult.text = "录音时间太短，没听清，请大声重试"
            return
        }

        tvVoiceStatus.text = "⏳ 正在识别语音..."
        pbVoiceLoading.visibility = View.VISIBLE
        tvVoiceResult.text = "正在连接百度云极速识别..."

        viewScope.launch {
            val recognizedList = baiduSpeechManager.recognizeSpeech(pcmBytes)
            pbVoiceLoading.visibility = View.GONE
            val rawText = recognizedList?.firstOrNull()
            val question = currentQuestion ?: return@launch

            if (rawText.isNullOrBlank()) {
                L.w("Baidu ASR returned empty result.")
                handleVoiceMismatch(question, null)
            } else {
                L.i("Baidu ASR returned: $rawText")
                val matchResult = VoiceAnswerMatcher.match(rawText, question.correctAnswer, question.acceptableAnswers)
                if (matchResult.isMatched) {
                    tvVoiceResult.text = "听到小朋友说：“${matchResult.cleanedText}” ✓ 回答正确！"
                    handleVoiceCorrectAnswer(question, matchResult.cleanedText)
                } else {
                    tvVoiceResult.text = "听到：“${matchResult.cleanedText}”"
                    handleVoiceMismatch(question, matchResult.cleanedText)
                }
            }
        }
    }

    /**
     * 语音未匹配/未听清处理（按题型配置的总作答次数，用完则判错并进入惩罚）
     */
    private fun handleVoiceMismatch(question: Question, recognizedText: String?) {
        val canRetry = registerWrongAttempt(question)
        if (canRetry) {
            val left = attemptsLeft(question)
            updateProgressLabel(question)
            tvVoiceStatus.text = getVoicePromptText()
            val hint = if (recognizedText != null) {
                "识别为“$recognizedText”，不对哦，再大声试一次！（还可试 $left 次）"
            } else {
                "没听清，请对准遥控器话筒大声再说一次！（还可试 $left 次）"
            }
            tvVoiceResult.text = hint
            L.i("Voice retry granted, remaining attempts: $left")
            // 每次未匹配都留一条流水，家长看板可见作答过程（错题本仅在最终判错时累计）
            recordAttemptAsync(question, recognizedText ?: "未识别", isCorrect = false, addToWrongBook = false)
        } else {
            L.i("Voice attempts exhausted, marking as wrong.")
            handleVoiceWrongAnswer(question, recognizedText)
        }
    }

    /**
     * 异步写入单次作答流水，并按需更新题目/错题本状态
     */
    private fun recordAttemptAsync(
        question: Question,
        answer: String,
        isCorrect: Boolean,
        addToWrongBook: Boolean,
        wrongBookAnswer: String = answer
    ) {
        val timeSpentSeconds = ((System.currentTimeMillis() - questionStartTime) / 1000L).toInt().coerceAtLeast(1)
        viewScope.launch(Dispatchers.IO) {
            answerRecordDao.insert(
                AnswerRecord(
                    questionId = question.id,
                    selectedAnswer = answer,
                    isCorrect = if (isCorrect) 1 else 0,
                    answerTime = System.currentTimeMillis(),
                    timeSpentSeconds = timeSpentSeconds
                )
            )
            if (isCorrect) {
                questionDao.markAnsweredCorrectly(question.id)
                wrongQuestionDao.markAsMastered(question.id)
            } else if (addToWrongBook) {
                wrongQuestionDao.recordWrongAnswer(question.id, wrongBookAnswer)
            }
        }
    }

    /**
     * 语音答对处理
     */
    private fun handleVoiceCorrectAnswer(question: Question, spokenText: String) {
        if (isSubmitting) return
        isSubmitting = true
        onAnsweredCorrectly()

        recordAttemptAsync(question, spokenText, isCorrect = true, addToWrongBook = false)

        btnVoiceRecord.setBackgroundResource(R.drawable.bg_option_btn_correct)
        layoutSuccess.visibility = View.VISIBLE
        layoutExplanation.visibility = View.GONE

        viewScope.launch {
            delay(1500L)
            answerResultListener?.onAnswerCorrect()
        }
    }

    /**
     * 语音答错处理（作答机会已用完）
     */
    private fun handleVoiceWrongAnswer(question: Question, spokenText: String?) {
        if (isSubmitting) return
        isSubmitting = true

        recordAttemptAsync(
            question,
            answer = spokenText ?: "未识别",
            isCorrect = false,
            addToWrongBook = true,
            wrongBookAnswer = spokenText ?: "语音未识别"
        )

        btnVoiceRecord.setBackgroundResource(R.drawable.bg_option_btn_wrong)
        tvFeedbackTitle.text = "❌ 回答错误！正确答案是：${question.correctAnswer}"
        tvExplanation.text = question.explanation ?: "标准答案为：${question.correctAnswer}"
        tvExplanation.visibility = View.VISIBLE

        layoutExplanation.visibility = View.VISIBLE
        layoutSuccess.visibility = View.GONE

        finishWithWrongAnswer()
    }

    /**
     * 作答机会用完后的统一收尾：先同步落盘惩罚状态，再更新界面
     */
    private fun finishWithWrongAnswer() {
        val penalized = onAttemptsExhausted()
        answerResultListener?.onAnswerWrong()

        if (penalized) {
            showPenaltyPanel(keepQuestionVisible = true)
        } else {
            startNextQuestionCountdown()
        }
    }

    /**
     * 未开启惩罚锁定（或练习模式）时：3 秒后自动载入下一题
     */
    private fun startNextQuestionCountdown() {
        tvCountdownNotice.visibility = View.VISIBLE
        countdownNextJob = viewScope.launch {
            for (sec in 3 downTo 1) {
                tvCountdownNotice.text = "$sec 秒后自动载入下一题..."
                delay(1000L)
            }
            loadNextQuestion()
        }
    }

    /**
     * 处理提交答案
     */
    private fun handleAnswerSubmission(selectedOption: String) {
        if (isSubmitting || isInPenalty) return
        val question = currentQuestion ?: return

        val isCorrect = selectedOption.equals(question.correctAnswer, ignoreCase = true)
        L.i("User submitted option $selectedOption for question #${question.id}, correct: $isCorrect, attemptsUsed: $attemptsUsed")

        if (isCorrect) {
            isSubmitting = true
            disableAllOptions()
            onAnsweredCorrectly()
            recordAttemptAsync(question, selectedOption, isCorrect = true, addToWrongBook = false)
            handleCorrectAnswerUi(selectedOption)
            return
        }

        // 答错：登记一次，判断是否还有机会
        val canRetry = registerWrongAttempt(question)
        if (canRetry) {
            recordAttemptAsync(question, selectedOption, isCorrect = false, addToWrongBook = false)
            handleRetryChoiceUi(selectedOption, question)
        } else {
            isSubmitting = true
            disableAllOptions()
            recordAttemptAsync(question, selectedOption, isCorrect = false, addToWrongBook = true)
            handleWrongAnswerUi(selectedOption, question)
        }
    }

    private fun disableAllOptions() {
        btnOptionA.isEnabled = false
        btnOptionB.isEnabled = false
        btnOptionC.isEnabled = false
        btnOptionD.isEnabled = false
    }

    /**
     * 选择题答错但仍有机会：选错项置灰禁用，不透露正确答案与解析
     */
    private fun handleRetryChoiceUi(selectedOption: String, question: Question) {
        getButtonByOption(selectedOption)?.apply {
            setBackgroundResource(R.drawable.bg_option_btn_wrong)
            isEnabled = false
        }

        val left = attemptsLeft(question)
        updateProgressLabel(question)
        tvFeedbackTitle.text = "❌ 答错了，还剩 $left 次机会，再想一想"
        tvExplanation.visibility = View.GONE
        tvCountdownNotice.visibility = View.GONE
        layoutExplanation.visibility = View.VISIBLE
        layoutSuccess.visibility = View.GONE

        // 选项被禁用后焦点会丢失，转移到第一个仍可作答的选项
        listOf(btnOptionA, btnOptionB, btnOptionC, btnOptionD)
            .firstOrNull { it.isEnabled }
            ?.let { FocusHelper.requestFocusSafe(it) }
    }

    /**
     * 答对处理
     */
    private fun handleCorrectAnswerUi(selectedOption: String) {
        getButtonByOption(selectedOption)?.setBackgroundResource(R.drawable.bg_option_btn_correct)

        layoutSuccess.visibility = View.VISIBLE
        layoutExplanation.visibility = View.GONE

        // 延时 1.5 秒后回调关闭悬浮窗，重新开启倒计时
        viewScope.launch {
            delay(1500L)
            answerResultListener?.onAnswerCorrect()
        }
    }

    /**
     * 答错处理（作答机会已用完）
     */
    private fun handleWrongAnswerUi(selectedOption: String, question: Question) {
        // 用户选错的标红，正确选项标绿
        getButtonByOption(selectedOption)?.setBackgroundResource(R.drawable.bg_option_btn_wrong)
        getButtonByOption(question.correctAnswer)?.setBackgroundResource(R.drawable.bg_option_btn_correct)

        tvFeedbackTitle.text = "❌ 回答错误！正确答案是 ${question.correctAnswer}"
        tvExplanation.text = question.explanation ?: "暂无详细解析"
        tvExplanation.visibility = View.VISIBLE

        layoutExplanation.visibility = View.VISIBLE
        layoutSuccess.visibility = View.GONE

        finishWithWrongAnswer()
    }

    private fun getButtonByOption(option: String): View? {
        return when (option.uppercase()) {
            "A" -> btnOptionA
            "B" -> btnOptionB
            "C" -> btnOptionC
            "D" -> btnOptionD
            else -> null
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        audioRecorder.stopRecording()
        viewScope.cancel()
    }
}
