package com.parent.tvlock.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
    private lateinit var btnSelectDate: TextView
    private lateinit var tvTotalDuration: TextView
    private var currentDate: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_usage_stats)

        appUsageRecordDao = AppUsageRecordDao(AppDatabaseHelper.getInstance(this))
        currentDate = DateTimeUtils.getTodayDate()

        val btnBack = findViewById<TextView>(R.id.btnBack)
        btnSelectDate = findViewById(R.id.btnSelectDate)
        tvTotalDuration = findViewById(R.id.tvTotalDuration)
        val rvAppUsage = findViewById<RecyclerView>(R.id.rvAppUsage)

        FocusHelper.setupFocusScale(btnBack)
        FocusHelper.setupFocusScale(btnSelectDate)

        btnBack.setOnClickListener { finish() }
        btnSelectDate.setOnClickListener { showDateSelectionDialog() }

        FocusHelper.requestFocusSafe(btnBack)

        rvAppUsage.layoutManager = LinearLayoutManager(this)
        adapter = AppUsageAdapter(emptyList())
        rvAppUsage.adapter = adapter

        loadUsageData(currentDate)
    }

    private fun loadUsageData(date: String) {
        currentDate = date
        val today = DateTimeUtils.getTodayDate()
        val isToday = (date == today)
        btnSelectDate.text = "📅 ${if (isToday) "今日" else date} (切换日期)"

        lifecycleScope.launch {
            val (summaries, totalSeconds) = withContext(Dispatchers.IO) {
                val list = appUsageRecordDao.getDailyUsageSummary(date)
                val total = appUsageRecordDao.getTotalUsageSeconds(date)
                Pair(list, total)
            }

            adapter.updateData(summaries)
            val dateLabel = if (isToday) "今日" else date
            tvTotalDuration.text = "${dateLabel}累计观看：${DateTimeUtils.formatDuration(totalSeconds)}"
        }
    }

    private fun showDateSelectionDialog() {
        lifecycleScope.launch {
            val dates = withContext(Dispatchers.IO) {
                val recorded = appUsageRecordDao.getAllRecordedDates().toMutableList()
                val today = DateTimeUtils.getTodayDate()
                if (!recorded.contains(today)) {
                    recorded.add(0, today)
                }
                recorded
            }

            val today = DateTimeUtils.getTodayDate()
            val displayLabels = dates.map { date ->
                if (date == today) "$date (今日)" else date
            }.toTypedArray()

            val selectedIndex = dates.indexOf(currentDate).coerceAtLeast(0)

            AlertDialog.Builder(this@AppUsageStatsActivity)
                .setTitle("选择查看日期")
                .setSingleChoiceItems(displayLabels, selectedIndex) { dialog, which ->
                    dialog.dismiss()
                    val pickedDate = dates[which]
                    loadUsageData(pickedDate)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
