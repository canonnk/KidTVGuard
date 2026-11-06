package com.parent.tvlock.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.parent.tvlock.R
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.dao.AppUsageRecordDao
import com.parent.tvlock.ui.adapter.AppUsageAdapter
import com.parent.tvlock.util.DateTimeUtils
import com.parent.tvlock.util.FocusHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppUsageStatsActivity : AppCompatActivity() {

    private lateinit var appUsageRecordDao: AppUsageRecordDao
    private lateinit var adapter: AppUsageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_usage_stats)

        appUsageRecordDao = AppUsageRecordDao(AppDatabaseHelper.getInstance(this))

        val btnBack = findViewById<TextView>(R.id.btnBack)
        val tvTotalDuration = findViewById<TextView>(R.id.tvTotalDuration)
        val rvAppUsage = findViewById<RecyclerView>(R.id.rvAppUsage)

        FocusHelper.setupFocusScale(btnBack)
        btnBack.setOnClickListener { finish() }
        FocusHelper.requestFocusSafe(btnBack)

        rvAppUsage.layoutManager = LinearLayoutManager(this)
        adapter = AppUsageAdapter(emptyList())
        rvAppUsage.adapter = adapter

        val today = DateTimeUtils.getTodayDate()

        lifecycleScope.launch {
            val (summaries, totalSeconds) = withContext(Dispatchers.IO) {
                val list = appUsageRecordDao.getDailyUsageSummary(today)
                val total = appUsageRecordDao.getTodayTotalUsageSeconds()
                Pair(list, total)
            }

            adapter.updateData(summaries)
            tvTotalDuration.text = "今日累计观看：${DateTimeUtils.formatDuration(totalSeconds)}"
        }
    }
}
