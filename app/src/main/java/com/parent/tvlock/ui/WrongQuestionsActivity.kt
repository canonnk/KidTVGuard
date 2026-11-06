package com.parent.tvlock.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.parent.tvlock.R
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.dao.WrongQuestionDao
import com.parent.tvlock.ui.adapter.WrongQuestionAdapter
import com.parent.tvlock.util.FocusHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WrongQuestionsActivity : AppCompatActivity() {

    private lateinit var wrongQuestionDao: WrongQuestionDao
    private lateinit var adapter: WrongQuestionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrong_questions)

        wrongQuestionDao = WrongQuestionDao(AppDatabaseHelper.getInstance(this))

        val btnBack = findViewById<TextView>(R.id.btnBack)
        val tvWrongSummary = findViewById<TextView>(R.id.tvWrongSummary)
        val rvWrongQuestions = findViewById<RecyclerView>(R.id.rvWrongQuestions)

        FocusHelper.setupFocusScale(btnBack)
        btnBack.setOnClickListener { finish() }
        FocusHelper.requestFocusSafe(btnBack)

        rvWrongQuestions.layoutManager = LinearLayoutManager(this)
        adapter = WrongQuestionAdapter(emptyList())
        rvWrongQuestions.adapter = adapter

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
}
