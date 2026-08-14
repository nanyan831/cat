package com.example.catlifepet.memory

import android.content.Intent
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.catlifepet.R
import com.example.catlifepet.auth.AuthActivity
import com.example.catlifepet.util.InteractionUtils.applySpringPressEffect
import com.example.catlifepet.util.ScreenUtils
import com.example.catlifepet.util.SystemBarUtils
import kotlinx.coroutines.launch

class MemoryActivity : ComponentActivity() {
    private val dataSource by lazy { MemoryGraph.source(this) }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var kindSpinner: Spinner
    private lateinit var memoryInput: EditText
    private lateinit var saveButton: Button
    private var busy = false
    private var canEdit = false

    private val background get() = ContextCompat.getColor(this, R.color.app_background)
    private val surface get() = ContextCompat.getColor(this, R.color.surface_primary)
    private val warmSurface get() = ContextCompat.getColor(this, R.color.surface_warm)
    private val primary get() = ContextCompat.getColor(this, R.color.primary)
    private val textPrimary get() = ContextCompat.getColor(this, R.color.text_primary)
    private val textSecondary get() = ContextCompat.getColor(this, R.color.text_secondary)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarUtils.applyLightBars(this, background, surface)
        setContentView(buildPage())
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized && !busy) load()
    }

    private fun buildPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MemoryActivity.background)
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        top.addView(button("‹") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(label("陪伴记忆", 19f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(64)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(28))
        }
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(label("只保存你明确填写的内容", 17f, true), margins(10))
        content.addView(label("小猫不会把聊天中的敏感推测自动保存为长期记忆。你可以随时查看或删除。", 14f, false, textSecondary), margins(14))
        kindSpinner = Spinner(this)
        kindSpinner.contentDescription = "记忆类型"
        kindSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, KIND_LABELS.map { it.first })
        content.addView(kindSpinner, cardParams(8))
        memoryInput = EditText(this).apply {
            hint = "例如：请叫我小雨"
            maxLines = 4
            minHeight = dp(52)
            contentDescription = "记忆内容"
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(surface, 8)
            applySpringPressEffect(pressedScale = 0.985f)
        }
        content.addView(memoryInput, cardParams(8))
        saveButton = primaryButton("保存这条记忆") { save() }
        content.addView(saveButton, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(18) })
        status = label("正在读取记忆…", 14f, false, textSecondary).apply { gravity = Gravity.CENTER }
        content.addView(status, margins(12))
        return root
    }

    private fun load() = launchAction { refresh() }

    private suspend fun refresh() {
        when (val outcome = dataSource.list()) {
            is MemoryOutcome.Success -> renderMemories(outcome.value)
            is MemoryOutcome.SessionExpired -> renderSessionExpired()
            is MemoryOutcome.Failure -> renderFailure(outcome.message)
        }
    }

    private fun save() {
        val value = memoryInput.text.toString().trim()
        if (value.isEmpty()) {
            memoryInput.error = "请先填写要保存的内容"
            return
        }
        val kind = KIND_LABELS[kindSpinner.selectedItemPosition].second
        launchAction {
            when (val outcome = dataSource.create(kind, value)) {
                is MemoryOutcome.Success -> {
                    memoryInput.text.clear()
                    refresh()
                }
                is MemoryOutcome.SessionExpired -> renderSessionExpired()
                is MemoryOutcome.Failure -> renderFailure(outcome.message)
            }
        }
    }

    private fun renderMemories(memories: List<CompanionMemory>) {
        canEdit = true
        updateEditorEnabled()
        removeDynamicViews()
        status.text = if (memories.isEmpty()) "还没有保存陪伴记忆。" else "已保存 ${memories.size} 条记忆"
        memories.forEach { memory ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(8), dp(12))
                minimumHeight = dp(64)
                background = rounded(surface, 8)
                contentDescription = "${kindLabel(memory.kind)}，${memory.content}"
                tag = DYNAMIC_TAG
            }
            val description = "${kindLabel(memory.kind)}\n${memory.content}"
            row.addView(label(description, 14f, false), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("删除") { confirmDelete(memory) }, LinearLayout.LayoutParams(dp(68), dp(48)))
            content.addView(row, cardParams(8))
        }
        val clearMemories = button("清空全部记忆") { confirmClearMemories() }.apply { tag = DYNAMIC_TAG }
        content.addView(clearMemories, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        val clearChats = button("清空全部云端聊天") { confirmClearConversations() }.apply { tag = DYNAMIC_TAG }
        content.addView(clearChats, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun confirmDelete(memory: CompanionMemory) {
        AlertDialog.Builder(this)
            .setTitle("删除这条记忆？")
            .setMessage(memory.content)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                launchAction { handleMutation(dataSource.delete(memory.id)) }
            }.show()
    }

    private fun confirmClearMemories() {
        AlertDialog.Builder(this)
            .setTitle("清空全部记忆？")
            .setMessage("删除后，小猫之后的回答不会再使用这些内容。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ -> launchAction { handleMutation(dataSource.deleteAll()) } }
            .show()
    }

    private fun confirmClearConversations() {
        AlertDialog.Builder(this)
            .setTitle("清空全部云端聊天？")
            .setMessage("所有云端会话和消息都会删除，本机缓存会在下次进入聊天时同步清理。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ -> launchAction { handleMutation(dataSource.clearConversations()) } }
            .show()
    }

    private suspend fun handleMutation(outcome: MemoryOutcome<Unit>) {
        when (outcome) {
            is MemoryOutcome.Success -> refresh()
            is MemoryOutcome.SessionExpired -> renderSessionExpired()
            is MemoryOutcome.Failure -> renderFailure(outcome.message)
        }
    }

    private fun renderSessionExpired() {
        canEdit = false
        updateEditorEnabled()
        removeDynamicViews()
        status.text = "登录后才能管理陪伴记忆。"
        content.addView(primaryButton("去登录") { startActivity(Intent(this, AuthActivity::class.java)) }.apply {
            tag = DYNAMIC_TAG
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun renderFailure(message: String) {
        canEdit = false
        updateEditorEnabled()
        removeDynamicViews()
        status.text = message
        content.addView(primaryButton("重试") { load() }.apply { tag = DYNAMIC_TAG }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun removeDynamicViews() {
        (content.childCount - 1 downTo 0)
            .filter { content.getChildAt(it).tag == DYNAMIC_TAG }
            .forEach(content::removeViewAt)
    }

    private fun launchAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        updateEditorEnabled()
        lifecycleScope.launch {
            try { block() } finally {
                busy = false
                updateEditorEnabled()
            }
        }
    }

    private fun updateEditorEnabled() {
        if (!::saveButton.isInitialized) return
        val enabled = canEdit && !busy
        saveButton.isEnabled = enabled
        saveButton.alpha = if (enabled) 1f else 0.5f
        memoryInput.isEnabled = enabled
        kindSpinner.isEnabled = enabled
    }

    private fun label(value: String, size: Float, bold: Boolean, color: Int = textPrimary) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        minHeight = dp(48)
        contentDescription = value
        setTextColor(textPrimary)
        background = rounded(warmSurface, 8)
        stateListAnimator = null
        setOnClickListener { action() }
        applySpringPressEffect()
    }

    private fun primaryButton(value: String, action: () -> Unit) = button(value, action).apply {
        setTextColor(Color.WHITE)
        background = rounded(primary, 8)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun margins(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun cardParams(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun dp(value: Int) = ScreenUtils.dp(this, value)
    private fun kindLabel(kind: String) = KIND_LABELS.firstOrNull { it.second == kind }?.first ?: "偏好"

    private companion object {
        const val DYNAMIC_TAG = "memory_dynamic"
        val KIND_LABELS = listOf(
            "我的昵称" to "nickname",
            "喜欢的称呼" to "preferred_address",
            "日常作息" to "routine",
            "个人偏好" to "preference"
        )
    }
}
