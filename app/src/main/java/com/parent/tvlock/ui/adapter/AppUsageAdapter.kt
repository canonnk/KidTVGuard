package com.parent.tvlock.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.parent.tvlock.R
import com.parent.tvlock.db.model.AppUsageSummary
import com.parent.tvlock.util.DateTimeUtils
import com.parent.tvlock.util.FocusHelper

class AppUsageAdapter(
    private var summaries: List<AppUsageSummary>
) : RecyclerView.Adapter<AppUsageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRank: TextView = view.findViewById(R.id.tvAppRank)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvAppPackage: TextView = view.findViewById(R.id.tvAppPackage)
        val tvAppDuration: TextView = view.findViewById(R.id.tvAppDuration)
        val tvLaunchCount: TextView = view.findViewById(R.id.tvLaunchCount)

        init {
            FocusHelper.setupCardRowFocus(view)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_usage, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val summary = summaries[position]
        holder.tvRank.text = (position + 1).toString()
        when (position) {
            0 -> holder.tvRank.setBackgroundResource(R.drawable.bg_ios_badge_orange)
            1 -> holder.tvRank.setBackgroundResource(R.drawable.bg_ios_badge_indigo)
            2 -> holder.tvRank.setBackgroundResource(R.drawable.bg_ios_badge_teal)
            else -> holder.tvRank.setBackgroundResource(R.drawable.bg_ios_badge_blue)
        }
        holder.tvAppName.text = summary.appName
        holder.tvAppPackage.text = summary.packageName
        holder.tvAppDuration.text = DateTimeUtils.formatDuration(summary.totalDurationSeconds)
        holder.tvLaunchCount.text = "累计开启 ${summary.launchCount} 次"
    }

    override fun getItemCount(): Int = summaries.size

    fun updateData(newList: List<AppUsageSummary>) {
        summaries = newList
        notifyDataSetChanged()
    }
}
