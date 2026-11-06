package com.parent.tvlock.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.parent.tvlock.R
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.dao.AnswerRecordDao
import com.parent.tvlock.ui.adapter.AnswerRecordAdapter
import com.parent.tvlock.util.FocusHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuestionHistoryActivity : AppCompatActivity() {

    private lateinit var answerRecordDao: AnswerRecordDao
    private lateinit var adapter: AnswerRecordAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_question_history)

        answerRecordDao = AnswerRecordDao(AppDatabaseHelper.getInstance(this))

        val btnBack = findViewById<TextView>(R.id.btnBack)
        val tvHeaderStats = findViewById<TextView>(R.id.tvHeaderStats)
        val rvAnswerHistory = findViewById<RecyclerView>(R.id.rvAnswerHistory)

        FocusHelper.setupFocusScale(btnBack)
        btnBack.setOnClickListener { finish() }
        FocusHelper.requestFocusSafe(btnBack)

        rvAnswerHistory.layoutManager = LinearLayoutManager(this)
        adapter = AnswerRecordAdapter(emptyList())
        rvAnswerHistory.adapter = adapter

        lifecycleScope.launch {
            val (records, stats) = withContext(Dispatchers.IO) {
                val list = answerRecordDao.getRecentRecords(100)
                val todayStats = answerRecordDao.getTodayStats()
                Pair(list, todayStats)
            }

            adapter.updateData(records)
            val total = stats.first
            val correct = stats.second
            val accuracy = if (total > 0) (correct * 100 / total) else 0
            tvHeaderStats.text = "今日已答 $total 题 · 答对 $correct 题 · 正确率 $accuracy%"
        }
    }
}
