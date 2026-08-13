package com.example.catlifepet.chat

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.catlifepet.R
import com.example.catlifepet.auth.AuthActivity
import com.example.catlifepet.util.ScreenUtils
import com.example.catlifepet.util.SystemBarUtils
import kotlinx.coroutines.launch

class ChatConversationListActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(ChatGraph.repository(this))
    }
    private lateinit var content: LinearLayout

    private val appBackground get() = ContextCompat.getColor(this, R.color.app_background)
    private val surface get() = ContextCompat.getColor(this, R.color.surface_primary)
    private val warmSurface get() = ContextCompat.getColor(this, R.color.surface_warm)
    private val primary get() = ContextCompat.getColor(this, R.color.primary)
    private val textPrimary get() = ContextCompat.getColor(this, R.color.text_primary)
    private val textSecondary get() = ContextCompat.getColor(this, R.color.text_secondary)
    private val divider get() = ContextCompat.getColor(this, R.color.divider_soft)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarUtils.applyLightBars(this, appBackground, surface)
        setContentView(buildContent())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun buildContent(): View {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(42), dp(20), dp(34))
        }
        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(appBackground)
            addView(content)
        }
    }

    private fun render(state: ChatUiState) {
        content.removeAllViews()
        addTopBar()
        content.addView(label("单独管理所有 AI 会话，选择一段继续和小猫聊天。", 14f, false, textSecondary), match(dp(16)))

        when (state.status) {
            ChatScreenStatus.LOADING -> {
                content.addView(ProgressBar(this), LinearLayout.LayoutParams(-1, dp(56)).apply {
                    bottomMargin = dp(12)
                })
            }
            ChatScreenStatus.SESSION_EXPIRED -> {
                content.addView(infoCard("登录已失效", "请重新登录后查看 AI 会话。"), match(dp(12)))
                content.addView(primaryButton("去登录") {
                    startActivity(AuthActivity.requiredLoginIntent(this))
                }, match(dp(12)))
                return
            }
            ChatScreenStatus.ERROR -> {
                content.addView(infoCard("暂时无法加载 AI 会话", state.message ?: "请稍后重试。"), match(dp(12)))
                content.addView(primaryButton("重试") { viewModel.load() }, match(dp(12)))
            }
            ChatScreenStatus.OFFLINE -> {
                content.addView(infoCard("当前离线", "显示最近缓存的 AI 会话。"), match(dp(12)))
            }
            ChatScreenStatus.READY -> Unit
        }

        if (state.conversations.isEmpty()) {
            content.addView(infoCard("还没有 AI 会话", "新建一段聊天后，它会出现在这里。"), match(dp(12)))
        } else {
            state.conversations.forEach { conversation ->
                content.addView(conversationCard(conversation, conversation.id == state.conversationId), match(dp(10)))
            }
        }

        content.addView(primaryButton("新建 AI 会话") {
            startActivity(Intent(this, ChatActivity::class.java))
        }, match(dp(12)))
    }

    private fun addTopBar() {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(actionButton("‹") { finish() }.apply { contentDescription = "返回" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(label("AI 会话", 22f, true, textPrimary), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(actionButton("＋") {
            startActivity(Intent(this, ChatActivity::class.java))
        }.apply { contentDescription = "新建 AI 会话" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(row, match(dp(16)))
    }

    private fun conversationCard(conversation: ChatConversation, selected: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(if (selected) warmSurface else surface, 22, divider)
            elevation = dp(1).toFloat()
            isClickable = true
            foreground = selectableItemBackground()
            addView(label(conversation.title?.takeIf(String::isNotBlank) ?: "未命名 AI 会话", 17f, true, textPrimary), match(dp(5)))
            addView(label("更新时间：${conversation.updatedAt}", 13f, false, textSecondary), match(dp(4)))
            addView(label(if (selected) "当前会话，点击继续" else "点击继续聊天", 13f, false, primary), match())
            setOnClickListener { openConversation(conversation.id) }
        }
    }

    private fun openConversation(conversationId: String) {
        startActivity(
            Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
        )
    }

    private fun infoCard(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(warmSurface, 22, divider)
            elevation = dp(1).toFloat()
            addView(label(title, 16f, true, textPrimary), match(dp(5)))
            addView(label(subtitle, 13f, false, textSecondary), match())
        }
    }

    private fun primaryButton(text: String, action: () -> Unit) = actionButton(text, action).apply {
        setTextColor(Color.WHITE)
        background = rounded(primary, 18)
        elevation = dp(2).toFloat()
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = if (text == "‹" || text == "＋") 25f else 15f
        isAllCaps = false
        minHeight = dp(48)
        setTextColor(textPrimary)
        background = rounded(surface, 18, divider)
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

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val out = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return ContextCompat.getDrawable(this, out.resourceId)
    }

    private fun match(bottomMargin: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        this.bottomMargin = bottomMargin
    }

    private fun dp(value: Int) = ScreenUtils.dp(this, value)
}
