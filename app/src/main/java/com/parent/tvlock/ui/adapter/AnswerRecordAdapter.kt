package com.parent.tvlock.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.parent.tvlock.R
import com.parent.tvlock.db.model.AnswerRecord
import com.parent.tvlock.util.DateTimeUtils
import com.parent.tvlock.util.FocusHelper

class AnswerRecordAdapter(
    private var records: List<AnswerRecord>
) : RecyclerView.Adapter<AnswerRecordAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSubject: TextView = view.findViewById(R.id.tvRecordSubject)
        val tvResult: TextView = view.findViewById(R.id.tvRecordResult)
        val tvTime: TextView = view.findViewById(R.id.tvRecordTime)
        val tvQuestionText: TextView = view.findViewById(R.id.tvRecordQuestionText)
        val tvChoice: TextView = view.findViewById(R.id.tvRecordChoice)
        val tvDuration: TextView = view.findViewById(R.id.tvRecordDuration)

        init {
            FocusHelper.setupFocusScale(view)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_answer_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        val context = holder.itemView.context
        val question = record.question

        holder.tvSubject.text = question?.subject ?: "综合"
        val isCorrect = record.isCorrect == 1

        if (isCorrect) {
            holder.tvResult.text = "✅ 回答正确"
            holder.tvResult.setTextColor(ContextCompat.getColor(context, R.color.ios_green))
        } else {
            holder.tvResult.text = "❌ 回答错误"
            holder.tvResult.setTextColor(ContextCompat.getColor(context, R.color.ios_red))
        }

        holder.tvTime.text = DateTimeUtils.formatDateTime(record.answerTime)
        holder.tvQuestionText.text = question?.questionText ?: "题目ID #${record.questionId}"

        val correctAns = question?.correctAnswer ?: "—"
        holder.tvChoice.text = "所选答案：${record.selectedAnswer} | 正确答案：$correctAns"
        holder.tvDuration.text = "作答耗时：${record.timeSpentSeconds}秒"
    }

    override fun getItemCount(): Int = records.size

    fun updateData(newRecords: List<AnswerRecord>) {
        records = newRecords
        notifyDataSetChanged()
    }
}
