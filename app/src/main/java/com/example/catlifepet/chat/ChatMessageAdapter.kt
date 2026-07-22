package com.example.catlifepet.chat

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.example.catlifepet.R
import com.example.catlifepet.util.ScreenUtils

class ChatMessageAdapter(
    private val onRetry: (ChatMessage) -> Unit
) : RecyclerView.Adapter<ChatMessageAdapter.MessageHolder>() {
    private var items: List<ChatMessage> = emptyList()

    init { setHasStableIds(true) }

    fun submit(messages: List<ChatMessage>) {
        val previous = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = messages.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous[oldItemPosition].id == messages[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous[oldItemPosition] == messages[newItemPosition]
        })
        items = messages
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()
    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder {
        return MessageHolder(MessageRow(parent.context, onRetry))
    }

    override fun onBindViewHolder(holder: MessageHolder, position: Int) {
        holder.row.bind(items[position])
    }

    class MessageHolder(val row: MessageRow) : RecyclerView.ViewHolder(row)
}

@SuppressLint("ViewConstructor")
class MessageRow(
    context: Context,
    private val onRetry: (ChatMessage) -> Unit
) : LinearLayout(context) {
    private val bubble = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(14), dp(11), dp(14), dp(10))
    }
    private val messageText = TextView(context).apply {
        textSize = 16f
        setLineSpacing(0f, 1.15f)
    }
    private val stateRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val stateText = TextView(context).apply { textSize = 12f }
    private val retry = Button(context).apply {
        text = "重试"
        textSize = 12f
        isAllCaps = false
        minHeight = dp(34)
        setPadding(dp(10), 0, dp(10), 0)
    }
    private var bound: ChatMessage? = null

    init {
        orientation = HORIZONTAL
        setPadding(dp(12), dp(5), dp(12), dp(5))
        bubble.addView(messageText, LayoutParams(-1, -2))
        stateRow.addView(stateText, LayoutParams(0, -2, 1f))
        stateRow.addView(retry, LayoutParams(-2, dp(34)))
        bubble.addView(stateRow, LayoutParams(-1, -2).apply { topMargin = dp(5) })
        addView(bubble, LayoutParams(0, -2, 0.82f))
        addView(TextView(context), LayoutParams(0, 1, 0.18f))
        retry.setOnClickListener { bound?.let(onRetry) }
    }

    fun bind(message: ChatMessage) {
        bound = message
        val user = message.role == "user"
        gravity = if (user) Gravity.END else Gravity.START
        if (user) {
            val first = getChildAt(0)
            val second = getChildAt(1)
            if (first === bubble) { removeAllViews(); addView(second); addView(bubble, LayoutParams(0, -2, 0.82f)) }
        } else if (getChildAt(0) !== bubble) {
            removeAllViews(); addView(bubble, LayoutParams(0, -2, 0.82f)); addView(TextView(context), LayoutParams(0, 1, 0.18f))
        }
        messageText.text = message.content.ifBlank { "…" }
        messageText.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        bubble.background = rounded(
            ContextCompat.getColor(context, if (user) R.color.primary_light else R.color.surface_primary),
            8
        )
        val state = when (message.status) {
            "sending" -> "正在发送…"
            "stopped" -> "已停止"
            "failed" -> message.errorMessage ?: "发送失败"
            "streaming" -> "小猫正在回复…"
            else -> ""
        }
        stateText.text = state
        stateText.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        stateText.setTypeface(stateText.typeface, if (message.status == "failed") Typeface.BOLD else Typeface.NORMAL)
        stateRow.visibility = if (state.isEmpty()) GONE else VISIBLE
        retry.visibility = if (message.pending && message.status in setOf("failed", "stopped")) VISIBLE else GONE
        retry.background = rounded(Color.TRANSPARENT, 8).apply {
            setStroke(dp(1), ContextCompat.getColor(context, R.color.primary))
        }
        retry.setTextColor(ContextCompat.getColor(context, R.color.primary))
        contentDescription = (if (user) "你说：" else "小猫说：") + message.content +
            if (state.isNotEmpty()) "，$state" else ""
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = ScreenUtils.dp(context, value)
}
