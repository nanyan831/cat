package com.example.catlifepet.chat

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
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
    private lateinit var layoutManager: LinearLayoutManager
    private val adapter = ChatMessageAdapter { viewModel.retry(it) }
    private var lastState = ChatUiState()
    private var lastRenderedMessageCount = 0
    private var lastAutoScrollAt = 0L

    private val appBackground get() = ContextCompat.getColor(this, R.color.app_background)
    private val surface get() = ContextCompat.getColor(this, R.color.surface_primary)
    private val primary get() = ContextCompat.getColor(this, R.color.primary)
    private val textPrimary get() = ContextCompat.getColor(this, R.color.text_primary)
    private val textSecondary get() = ContextCompat.getColor(this, R.color.text_secondary)
    private val divider get() = ContextCompat.getColor(this, R.color.divider_soft)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = appBackground
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildContent())
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let(viewModel::load)
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
            setPadding(dp(12), dp(26), dp(12), dp(8))
        }
        top.addView(iconButton("‹", "返回") { finish() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        title = label("和小猫聊聊", 19f, true, textPrimary).apply { gravity = Gravity.CENTER }
        top.addView(title, LinearLayout.LayoutParams(0, dp(46), 1f))
        top.addView(iconButton("☷", "聊天记录") { showHistory() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        top.addView(iconButton("＋", "新建聊天") { viewModel.newConversation() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(84)))

        status = label("正在准备聊天…", 13f, false, textSecondary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(7), dp(16), dp(7))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        stateAction = primaryButton("重试") { viewModel.load() }.apply { visibility = View.GONE }
        root.addView(stateAction, LinearLayout.LayoutParams(-1, dp(48)).apply {
            marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(6)
        })

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
                this@ChatActivity.layoutManager = this
            }
            adapter = this@ChatActivity.adapter
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
                addDuration = 180L
                moveDuration = 140L
                changeDuration = 0L
                removeDuration = 120L
                supportsChangeAnimations = false
            }
            clipToPadding = false
            setPadding(0, dp(10), 0, dp(12))
            contentDescription = "聊天消息"
        }
        root.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = rounded(surface, 24, divider)
            elevation = dp(6).toFloat()
        }
        input = EditText(this).apply {
            hint = "想和小猫说点什么？"
            contentDescription = "聊天输入框"
            textSize = 16f
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(appBackground, 18, divider)
            maxLines = 4
            minHeight = dp(54)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        sendButton = primaryButton("发送") { send() }
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(76), dp(54)))
        stopButton = actionButton("停止") { viewModel.stopGenerating() }.apply { visibility = View.GONE }
        composer.addView(stopButton, LinearLayout.LayoutParams(dp(76), dp(54)))
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
        val shouldFollow = shouldFollowConversation()
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
        adapter.submit(displayMessages) {
            val appended = displayMessages.size > lastRenderedMessageCount
            val now = SystemClock.uptimeMillis()
            val streamingFollow = state.generating && shouldFollow && now - lastAutoScrollAt > AUTO_SCROLL_INTERVAL_MS
            if (displayMessages.isNotEmpty() && (appended || streamingFollow)) {
                lastAutoScrollAt = now
                recycler.post {
                    if (appended) recycler.smoothScrollToPosition(displayMessages.lastIndex)
                    else recycler.scrollToPosition(displayMessages.lastIndex)
                }
            }
            lastRenderedMessageCount = displayMessages.size
        }

        val nextStatusText = when (state.status) {
            ChatScreenStatus.LOADING -> "正在准备聊天…"
            ChatScreenStatus.READY -> state.message ?: if (displayMessages.isEmpty()) "小猫在这里，慢慢说就好。" else ""
            ChatScreenStatus.OFFLINE -> state.message ?: "当前离线，显示最近聊天记录。"
            ChatScreenStatus.SESSION_EXPIRED -> state.message ?: "登录后才能继续聊天。"
            ChatScreenStatus.ERROR -> state.message ?: "聊天暂时不可用。"
        }
        if (status.text.toString() != nextStatusText) {
            status.animate().cancel()
            status.alpha = 0.35f
            status.text = nextStatusText
            status.animate().alpha(1f).setDuration(140L).setInterpolator(DecelerateInterpolator()).start()
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        stateAction.visibility = if (state.status in setOf(
                ChatScreenStatus.OFFLINE,
                ChatScreenStatus.SESSION_EXPIRED,
                ChatScreenStatus.ERROR
            )) View.VISIBLE else View.GONE
        stateAction.text = if (state.status == ChatScreenStatus.SESSION_EXPIRED) "去登录" else "重试"
        stateAction.contentDescription = stateAction.text
        stateAction.setOnClickListener {
            if (state.status == ChatScreenStatus.SESSION_EXPIRED) {
                startActivity(Intent(this, AuthActivity::class.java))
            } else viewModel.load()
        }
        val ready = state.status == ChatScreenStatus.READY && state.conversationId != null
        input.isEnabled = ready && !state.generating
        crossfadeActionButtons(state.generating)
        sendButton.isEnabled = ready
        sendButton.alpha = if (ready) 1f else 0.5f
    }

    private fun shouldFollowConversation(): Boolean {
        if (!::layoutManager.isInitialized || adapter.itemCount == 0) return true
        val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: layoutManager.findLastVisibleItemPosition()
        return adapter.itemCount - lastVisible <= 3
    }

    private fun crossfadeActionButtons(generating: Boolean) {
        setButtonVisible(sendButton, !generating)
        setButtonVisible(stopButton, generating)
    }

    private fun setButtonVisible(button: Button, visible: Boolean) {
        val targetVisibility = if (visible) View.VISIBLE else View.GONE
        if (button.visibility == targetVisibility) return
        if (visible) {
            button.alpha = 0f
            button.scaleX = 0.96f
            button.scaleY = 0.96f
            button.visibility = View.VISIBLE
            button.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            button.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(110L)
                .withEndAction { button.visibility = View.GONE }
                .start()
        }
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
        background = rounded(primary, 18)
        elevation = dp(2).toFloat()
    }

    private fun actionButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 14f
        isAllCaps = false
        minHeight = dp(48)
        contentDescription = value
        setTextColor(textPrimary)
        background = rounded(appBackground, 18, divider)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float, bold: Boolean, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = ScreenUtils.dp(this, value)

    companion object {
        const val EXTRA_CONVERSATION_ID = "com.example.catlifepet.chat.extra.CONVERSATION_ID"
        private const val AUTO_SCROLL_INTERVAL_MS = 220L
    }
}
