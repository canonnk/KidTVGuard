package com.parent.tvlock.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.parent.tvlock.R
import com.parent.tvlock.db.model.WrongQuestion
import com.parent.tvlock.util.FocusHelper

class WrongQuestionAdapter(
    private var wrongQuestions: List<WrongQuestion>,
    private val onItemClick: ((WrongQuestion) -> Unit)? = null
) : RecyclerView.Adapter<WrongQuestionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSubject: TextView = view.findViewById(R.id.tvWrongSubject)
        val tvWrongCount: TextView = view.findViewById(R.id.tvWrongCount)
        val tvMasterStatus: TextView = view.findViewById(R.id.tvMasterStatus)
        val tvQuestionText: TextView = view.findViewById(R.id.tvWrongQuestionText)
        val tvExplanation: TextView = view.findViewById(R.id.tvWrongExplanation)

        init {
            FocusHelper.setupCardRowFocus(view)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_wrong_question, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = wrongQuestions[position]
        val context = holder.itemView.context
        val question = item.question

        val isVoice = question?.isVoiceType == true
        val subject = question?.subject ?: "错题"
        holder.tvSubject.text = if (isVoice) "$subject · 语音认读" else subject
        holder.tvWrongCount.text = "累计做错：${item.wrongCount} 次"

        val isMastered = item.isMastered == 1
        if (isMastered) {
            holder.tvMasterStatus.text = "🏆 已攻克掌握"
            holder.tvMasterStatus.setTextColor(ContextCompat.getColor(context, R.color.ios_green))
        } else {
            holder.tvMasterStatus.text = "🔥 重点复习"
            holder.tvMasterStatus.setTextColor(ContextCompat.getColor(context, R.color.ios_orange))
        }

        holder.tvQuestionText.text = question?.questionText ?: "题目 #${item.questionId}"
        val exp = question?.explanation ?: "暂无解析"
        val wrongAnsDesc = if (isVoice) "最近识别：\"${item.lastWrongAnswer}\"" else "最近选错：${item.lastWrongAnswer}"
        holder.tvExplanation.text = "解析：$exp ($wrongAnsDesc)"

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(item)
        }
    }

    override fun getItemCount(): Int = wrongQuestions.size

    fun updateData(newList: List<WrongQuestion>) {
        wrongQuestions = newList
        notifyDataSetChanged()
    }
}
