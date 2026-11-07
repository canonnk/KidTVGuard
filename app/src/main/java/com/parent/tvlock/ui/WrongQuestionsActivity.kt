package com.parent.tvlock.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.parent.tvlock.R
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.dao.WrongQuestionDao
import com.parent.tvlock.db.model.WrongQuestion
import com.parent.tvlock.overlay.OverlayLockManager
import com.parent.tvlock.overlay.QuestionOverlayView
import com.parent.tvlock.ui.adapter.WrongQuestionAdapter
import com.parent.tvlock.util.FocusHelper
import com.parent.tvlock.util.ParentAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WrongQuestionsActivity : AppCompatActivity() {

    private lateinit var wrongQuestionDao: WrongQuestionDao
    private lateinit var adapter: WrongQuestionAdapter
    private lateinit var tvWrongSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrong_questions)

        wrongQuestionDao = WrongQuestionDao(AppDatabaseHelper.getInstance(this))

        val btnBack = findViewById<TextView>(R.id.btnBack)
        tvWrongSummary = findViewById(R.id.tvWrongSummary)
        val rvWrongQuestions = findViewById<RecyclerView>(R.id.rvWrongQuestions)

        FocusHelper.setupFocusScale(btnBack)
        btnBack.setOnClickListener { finish() }
        FocusHelper.requestFocusSafe(btnBack)

        rvWrongQuestions.layoutManager = LinearLayoutManager(this)
        adapter = WrongQuestionAdapter(emptyList()) { wrongQuestion ->
            showWrongQuestionDialog(wrongQuestion)
        }
        rvWrongQuestions.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val (list, unmasteredCount) = withContext(Dispatchers.IO) {
                val allWrong = wrongQuestionDao.getAllWrongQuestions(onlyUnmastered = false)
                val count = wrongQuestionDao.getUnmasteredCount()
                Pair(allWrong, count)
            }

            adapter.updateData(list)
            tvWrongSummary.text = "待巩固消灭错题：$unmasteredCount 道 · 总计错题：${list.size} 道"
        }
    }

    private fun showWrongQuestionDialog(wrongQuestion: WrongQuestion) {
        val q = wrongQuestion.question
        val isVoice = q?.isVoiceType == true
        val details = buildString {
            append("【题目】\n${q?.questionText ?: "题目 #${wrongQuestion.questionId}"}\n\n")
            if (!isVoice && q != null) {
                append("【选项】\nA: ${q.optionA}\nB: ${q.optionB}\nC: ${q.optionC}\nD: ${q.optionD}\n\n")
            }
            append("【正确答案】${q?.correctAnswer ?: "—"}\n")
            if (isVoice) {
                append("【最近识别】\"${wrongQuestion.lastWrongAnswer}\"\n")
            } else {
                append("【最近错选】${wrongQuestion.lastWrongAnswer}\n")
            }
            append("【累计做错】${wrongQuestion.wrongCount} 次\n\n")
            append("【解析】${q?.explanation ?: "暂无详细解析"}")
        }

        AlertDialog.Builder(this)
            .setTitle(if (isVoice) "错题详情 · ${q?.subject ?: "综合"} (语音题)" else "错题详情 · ${q?.subject ?: "综合"}")
            .setMessage(details)
            .setPositiveButton("立即重练此题") { _, _ ->
                if (isVoice && !QuestionOverlayView.isVoiceQuestionEligible(this)) {
                    Toast.makeText(
                        this,
                        "当前未授麦克风权限、未联网或未配置百度语音 Key，暂无法重练此语音题",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                val lockManager = OverlayLockManager.getInstance(this)
                if (lockManager.canDrawOverlays()) {
                    lockManager.showLock(targetQuestionId = wrongQuestion.questionId)
                } else {
                    Toast.makeText(this, "未授予悬浮窗权限，无法拉起锁屏", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) {
            ParentAuthManager.revokePass()
            finish()
        }
    }
}
