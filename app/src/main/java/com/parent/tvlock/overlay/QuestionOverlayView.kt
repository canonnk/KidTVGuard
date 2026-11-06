package com.parent.tvlock.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.parent.tvlock.R
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.dao.AnswerRecordDao
import com.parent.tvlock.db.dao.QuestionDao
import com.parent.tvlock.db.dao.WrongQuestionDao
import com.parent.tvlock.db.model.AnswerRecord
import com.parent.tvlock.db.model.Question
import com.parent.tvlock.net.MemoryImageLoader
import com.parent.tvlock.util.FocusHelper
import com.parent.tvlock.util.L
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

    private var answerResultListener: OnAnswerResultListener? = null

    // UI 控件
    private val tvSubjectBadge: TextView
    private val tvQuestionProgress: TextView
    private val tvQuestionText: TextView
    private val ivQuestionImage: ImageView

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

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_question_overlay, this, true)

        isFocusable = true
        isFocusableInTouchMode = true

        tvSubjectBadge = view.findViewById(R.id.tvSubjectBadge)
        tvQuestionProgress = view.findViewById(R.id.tvQuestionProgress)
        tvQuestionText = view.findViewById(R.id.tvQuestionText)
        ivQuestionImage = view.findViewById(R.id.ivQuestionImage)

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

        layoutExplanation = view.findViewById(R.id.layoutExplanation)
        tvFeedbackTitle = view.findViewById(R.id.tvFeedbackTitle)
        tvExplanation = view.findViewById(R.id.tvExplanation)
        tvCountdownNotice = view.findViewById(R.id.tvCountdownNotice)

        layoutSuccess = view.findViewById(R.id.layoutSuccess)
        tvSuccessNotice = view.findViewById(R.id.tvSuccessNotice)

        // 遥控器焦点放大与缩放动效
        FocusHelper.setupFocusScaleBatch(btnOptionA, btnOptionB, btnOptionC, btnOptionD)

        // 点击与遥控器 OK 键事件绑定
        btnOptionA.setOnClickListener { handleAnswerSubmission("A") }
        btnOptionB.setOnClickListener { handleAnswerSubmission("B") }
        btnOptionC.setOnClickListener { handleAnswerSubmission("C") }
        btnOptionD.setOnClickListener { handleAnswerSubmission("D") }

        // 载入第一道题目
        loadNextQuestion()
    }

    /**
     * 遥控器按键全局拦截：严防用户按下遥控器返回键逃逸
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            L.d("KEYCODE_BACK intercepted in QuestionOverlayView.")
            return true // 完全消耗返回键事件，禁止退出
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
        viewScope.cancel()
    }
}
