package com.example.catlifepet.chat

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.catlifepet.R
import com.example.catlifepet.auth.AuthActivity
import com.example.catlifepet.util.ScreenUtils
import kotlinx.coroutines.launch

class ChatActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(ChatGraph.repository(this))
    }
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var stateAction: Button
    private val adapter = ChatMessageAdapter { viewModel.retry(it) }
    private var lastState = ChatUiState()

    private val appBackground get() = ContextCompat.getColor(this, R.color.app_background)
    private val surface get() = ContextCompat.getColor(this, R.color.surface_primary)
    private val primary get() = ContextCompat.getColor(this, R.color.primary)
    private val textPrimary get() = ContextCompat.getColor(this, R.color.text_primary)
    private val textSecondary get() = ContextCompat.getColor(this, R.color.text_secondary)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = appBackground
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildContent())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(appBackground)
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        top.addView(iconButton("‹", "返回") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        title = label("和小猫聊聊", 19f, true, textPrimary).apply { gravity = Gravity.CENTER }
        top.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(iconButton("☷", "聊天记录") { showHistory() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(iconButton("＋", "新建聊天") { viewModel.newConversation() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(64)))

        status = label("正在准备聊天…", 13f, false, textSecondary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(7), dp(16), dp(7))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        stateAction = primaryButton("重试") { viewModel.load() }.apply { visibility = View.GONE }
        root.addView(stateAction, LinearLayout.LayoutParams(-1, dp(44)).apply {
            marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(6)
        })

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
            adapter = this@ChatActivity.adapter
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
            contentDescription = "聊天消息"
        }
        root.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = rounded(surface, 0)
        }
        input = EditText(this).apply {
            hint = "想和小猫说点什么？"
            textSize = 16f
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(appBackground, 8)
            maxLines = 4
            minHeight = dp(48)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        sendButton = primaryButton("发送") { send() }
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(68), dp(48)))
        stopButton = actionButton("停止") { viewModel.stopGenerating() }.apply { visibility = View.GONE }
        composer.addView(stopButton, LinearLayout.LayoutParams(dp(68), dp(48)))
        root.addView(composer, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun send() {
        val value = input.text.toString()
        if (value.isBlank() || lastState.generating) return
        input.text.clear()
        viewModel.send(value)
    }

    private fun render(state: ChatUiState) {
        lastState = state
        val conversation = state.conversations.firstOrNull { it.id == state.conversationId }
        title.text = conversation?.title?.takeIf(String::isNotBlank) ?: "和小猫聊聊"
        val displayMessages = state.messages.toMutableList()
        if (state.assistantDraft.isNotEmpty()) {
            displayMessages += ChatMessage(
                id = state.assistantDraftId ?: "assistant-draft",
                conversationId = state.conversationId.orEmpty(),
                sequenceNumber = Long.MAX_VALUE,
                role = "assistant",
                content = state.assistantDraft,
                status = "streaming"
            )
        }
        adapter.submit(displayMessages)
        if (displayMessages.isNotEmpty()) recycler.scrollToPosition(displayMessages.lastIndex)

        status.text = when (state.status) {
            ChatScreenStatus.LOADING -> "正在准备聊天…"
            ChatScreenStatus.READY -> state.message ?: if (displayMessages.isEmpty()) "小猫在这里，慢慢说就好。" else ""
            ChatScreenStatus.OFFLINE -> state.message ?: "当前离线，显示最近聊天记录。"
            ChatScreenStatus.SESSION_EXPIRED -> state.message ?: "登录后才能继续聊天。"
            ChatScreenStatus.ERROR -> state.message ?: "聊天暂时不可用。"
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        stateAction.visibility = if (state.status in setOf(
                ChatScreenStatus.OFFLINE,
                ChatScreenStatus.SESSION_EXPIRED,
                ChatScreenStatus.ERROR
            )) View.VISIBLE else View.GONE
        stateAction.text = if (state.status == ChatScreenStatus.SESSION_EXPIRED) "去登录" else "重试"
        stateAction.setOnClickListener {
            if (state.status == ChatScreenStatus.SESSION_EXPIRED) {
                startActivity(Intent(this, AuthActivity::class.java))
            } else viewModel.load()
        }
        val ready = state.status == ChatScreenStatus.READY && state.conversationId != null
        input.isEnabled = ready && !state.generating
        sendButton.visibility = if (state.generating) View.GONE else View.VISIBLE
        stopButton.visibility = if (state.generating) View.VISIBLE else View.GONE
        sendButton.isEnabled = ready
        sendButton.alpha = if (ready) 1f else 0.5f
    }

    private fun showHistory() {
        val items = lastState.conversations
        if (items.isEmpty()) return
        val labels = items.map { it.title?.takeIf(String::isNotBlank) ?: "未命名聊天" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("聊天记录")
            .setItems(labels) { _, index -> viewModel.selectConversation(items[index].id) }
            .setNegativeButton("取消", null)
            .setNeutralButton("删除当前聊天") { _, _ -> confirmDelete() }
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除这段聊天？")
            .setMessage("云端和本机缓存中的这段记录都会被删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> viewModel.deleteCurrentConversation() }
            .show()
    }

    private fun iconButton(value: String, description: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 25f
        contentDescription = description
        setTextColor(textPrimary)
        setPadding(0, 0, 0, 0)
        background = rounded(Color.TRANSPARENT, 8)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun primaryButton(value: String, action: () -> Unit) = actionButton(value, action).apply {
        setTextColor(Color.WHITE)
        background = rounded(primary, 8)
    }

    private fun actionButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 14f
        isAllCaps = false
        setTextColor(textPrimary)
        background = rounded(appBackground, 8)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float, bold: Boolean, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int) = ScreenUtils.dp(this, value)
}
