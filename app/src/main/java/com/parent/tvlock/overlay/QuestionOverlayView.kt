package com.parent.tvlock.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
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
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnAnswerResultListener {
        fun onAnswerCorrect()
        fun onAnswerWrong()
    }

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var countdownNextJob: Job? = null

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val questionDao = QuestionDao(dbHelper)
    private val wrongQuestionDao = WrongQuestionDao(dbHelper)
    private val answerRecordDao = AnswerRecordDao(dbHelper)
    private val preferenceManager = PreferenceManager.getInstance(context)
    private val baiduSpeechManager = BaiduSpeechManager.getInstance(context)
    private val audioRecorder = AudioRecorderHelper(viewScope)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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

    // 运行态数据
    private var currentQuestion: Question? = null
    private var questionStartTime: Long = 0L
    private var isSubmitting: Boolean = false
    private var remainingRetries: Int = 2
    private var isVoiceRecording: Boolean = false
    private var isMutedByVoice: Boolean = false

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_question_overlay, this, true)

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

        // 遥控器焦点放大与缩放动效
        FocusHelper.setupFocusScaleBatch(btnOptionA, btnOptionB, btnOptionC, btnOptionD, btnVoiceRecord)

        // 选择题点击与遥控器 OK 键事件绑定
        btnOptionA.setOnClickListener { handleAnswerSubmission("A") }
        btnOptionB.setOnClickListener { handleAnswerSubmission("B") }
        btnOptionC.setOnClickListener { handleAnswerSubmission("C") }
        btnOptionD.setOnClickListener { handleAnswerSubmission("D") }

        // 语音题触屏长按/点击兼容处理（在支持触屏或鼠标的设备上同样可用）
        setupVoiceRecordButtonTouch()

        // 载入第一道题目
        loadNextQuestion()
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
     * 遥控器按键全局拦截：严防返回键逃逸，并支持遥控器语音键/OK键触发语音录制
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            L.d("KEYCODE_BACK intercepted in QuestionOverlayView.")
            return true // 完全消耗返回键事件，禁止退出
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

    fun setOnAnswerResultListener(listener: OnAnswerResultListener) {
        this.answerResultListener = listener
    }

    /**
     * 抽取并加载下一道题目
     */
    private fun loadNextQuestion() {
        countdownNextJob?.cancel()
        isSubmitting = false

        // 重置选项背景与状态
        resetOptionsUi()

        viewScope.launch {
            val question = withContext(Dispatchers.IO) {
                // 优先从错题本中抽取待巩固的错题
                val wrongQuestionId = wrongQuestionDao.getRandomUnmasteredQuestionId()
                if (wrongQuestionId != null && Math.random() < 0.5) {
                    val q = questionDao.getQuestionById(wrongQuestionId)
                    if (q != null) return@withContext Pair(q, true)
                }

                // 若无错题或按概率走全库抽取未答对题
                val randomQ = questionDao.getRandomQuestionForLock()
                Pair(randomQ, false)
            }

            val targetQ = question.first
            val isFromWrongBook = question.second

            if (targetQ == null) {
                L.w("No question available in database!")
                tvQuestionText.text = "暂无可用题目，请确保已同步题库。"
                return@launch
            }

            currentQuestion = targetQ
            questionStartTime = System.currentTimeMillis()

            bindQuestionToUi(targetQ, isFromWrongBook)
        }
    }

    /**
     * 将题目数据渲染到界面视图
     */
    private fun bindQuestionToUi(question: Question, isFromWrongBook: Boolean) {
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

        tvQuestionProgress.text = if (isFromWrongBook) "错题重练巩固" else "防沉迷随机抽题"
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
            remainingRetries = preferenceManager.voiceRetryCount
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
        layoutSuccess.visibility = View.GONE
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

        // 录音时静音电视背景音，避免动画片噪音干扰麦克风（已获用户确认）
        try {
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true)
            isMutedByVoice = true
            L.i("Muted TV STREAM_MUSIC during voice recording.")
        } catch (e: Exception) {
            L.w("Failed to mute TV audio", tr = e)
        }

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
            unmuteTvAudio()
            isVoiceRecording = false
            countdownNextJob?.cancel()
            resetVoiceUi()
            tvVoiceResult.text = "麦克风启动失败，请检查录音权限！"
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
        unmuteTvAudio()

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
     * 语音未匹配/未听清处理（支持 2 次重试机制）
     */
    private fun handleVoiceMismatch(question: Question, recognizedText: String?) {
        if (remainingRetries > 0) {
            remainingRetries--
            tvVoiceStatus.text = getVoicePromptText()
            val hint = if (recognizedText != null) {
                "识别为“$recognizedText”，不对哦，再大声试一次！（还可试 ${remainingRetries + 1} 次）"
            } else {
                "没听清，请对准遥控器话筒大声再说一次！（还可试 ${remainingRetries + 1} 次）"
            }
            tvVoiceResult.text = hint
            L.i("Voice retry granted, remaining attempts: $remainingRetries")
        } else {
            L.i("Voice retries exhausted, marking as wrong.")
            handleVoiceWrongAnswer(question, recognizedText)
        }
    }

    /**
     * 语音答对处理
     */
    private fun handleVoiceCorrectAnswer(question: Question, spokenText: String) {
        if (isSubmitting) return
        isSubmitting = true

        val timeSpentSeconds = ((System.currentTimeMillis() - questionStartTime) / 1000L).toInt().coerceAtLeast(1)
        viewScope.launch(Dispatchers.IO) {
            val record = AnswerRecord(
                questionId = question.id,
                selectedAnswer = spokenText,
                isCorrect = 1,
                answerTime = System.currentTimeMillis(),
                timeSpentSeconds = timeSpentSeconds
            )
            answerRecordDao.insert(record)
            questionDao.markAnsweredCorrectly(question.id)
            wrongQuestionDao.markAsMastered(question.id)
        }

        btnVoiceRecord.setBackgroundResource(R.drawable.bg_option_btn_correct)
        layoutSuccess.visibility = View.VISIBLE
        layoutExplanation.visibility = View.GONE

        viewScope.launch {
            delay(1500L)
            answerResultListener?.onAnswerCorrect()
        }
    }

    /**
     * 语音答错处理
     */
    private fun handleVoiceWrongAnswer(question: Question, spokenText: String?) {
        if (isSubmitting) return
        isSubmitting = true

        val timeSpentSeconds = ((System.currentTimeMillis() - questionStartTime) / 1000L).toInt().coerceAtLeast(1)
        viewScope.launch(Dispatchers.IO) {
            val record = AnswerRecord(
                questionId = question.id,
                selectedAnswer = spokenText ?: "未识别",
                isCorrect = 0,
                answerTime = System.currentTimeMillis(),
                timeSpentSeconds = timeSpentSeconds
            )
            answerRecordDao.insert(record)
            wrongQuestionDao.recordWrongAnswer(question.id, spokenText ?: "语音未识别")
        }

        btnVoiceRecord.setBackgroundResource(R.drawable.bg_option_btn_wrong)
        tvFeedbackTitle.text = "❌ 回答错误！正确答案是：${question.correctAnswer}"
        tvExplanation.text = question.explanation ?: "标准答案为：${question.correctAnswer}"

        layoutExplanation.visibility = View.VISIBLE
        layoutSuccess.visibility = View.GONE

        answerResultListener?.onAnswerWrong()

        countdownNextJob = viewScope.launch {
            for (sec in 3 downTo 1) {
                tvCountdownNotice.text = "$sec 秒后自动载入下一题..."
                delay(1000L)
            }
            loadNextQuestion()
        }
    }

    private fun unmuteTvAudio() {
        if (isMutedByVoice) {
            try {
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
                isMutedByVoice = false
                L.i("Restored TV STREAM_MUSIC volume.")
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /**
     * 处理提交答案
     */
    private fun handleAnswerSubmission(selectedOption: String) {
        if (isSubmitting) return
        val question = currentQuestion ?: return
        isSubmitting = true

        // 禁用选项避免重复连点
        btnOptionA.isEnabled = false
        btnOptionB.isEnabled = false
        btnOptionC.isEnabled = false
        btnOptionD.isEnabled = false

        val timeSpentSeconds = ((System.currentTimeMillis() - questionStartTime) / 1000L).toInt().coerceAtLeast(1)
        val isCorrect = selectedOption.equals(question.correctAnswer, ignoreCase = true)

        L.i("User submitted option $selectedOption for question #${question.id}, correct: $isCorrect, timeSpent: ${timeSpentSeconds}s")

        // 1. 异步写入 SQLite 数据库（记录流水、更新错题本与掌握状态）
        viewScope.launch(Dispatchers.IO) {
            // 写入流水日志
            val record = AnswerRecord(
                questionId = question.id,
                selectedAnswer = selectedOption,
                isCorrect = if (isCorrect) 1 else 0,
                answerTime = System.currentTimeMillis(),
                timeSpentSeconds = timeSpentSeconds
            )
            answerRecordDao.insert(record)

            if (isCorrect) {
                // 标记题目已答对
                questionDao.markAnsweredCorrectly(question.id)
                // 若在错题本中，标记为已攻克
                wrongQuestionDao.markAsMastered(question.id)
            } else {
                // 记录或累加至错题本
                wrongQuestionDao.recordWrongAnswer(question.id, selectedOption)
            }
        }

        // 2. 界面状态与遥控器反馈
        if (isCorrect) {
            handleCorrectAnswerUi(selectedOption)
        } else {
            handleWrongAnswerUi(selectedOption, question)
        }
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
     * 答错处理
     */
    private fun handleWrongAnswerUi(selectedOption: String, question: Question) {
        // 用户选错的标红，正确选项标绿
        getButtonByOption(selectedOption)?.setBackgroundResource(R.drawable.bg_option_btn_wrong)
        getButtonByOption(question.correctAnswer)?.setBackgroundResource(R.drawable.bg_option_btn_correct)

        tvFeedbackTitle.text = "❌ 回答错误！正确答案是 ${question.correctAnswer}"
        tvExplanation.text = question.explanation ?: "暂无详细解析"

        layoutExplanation.visibility = View.VISIBLE
        layoutSuccess.visibility = View.GONE

        answerResultListener?.onAnswerWrong()

        // 倒计时 3 秒后刷新下一道题目
        countdownNextJob = viewScope.launch {
            for (sec in 3 downTo 1) {
                tvCountdownNotice.text = "$sec 秒后自动载入下一题..."
                delay(1000L)
            }
            loadNextQuestion()
        }
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
        unmuteTvAudio()
        viewScope.cancel()
    }
}
