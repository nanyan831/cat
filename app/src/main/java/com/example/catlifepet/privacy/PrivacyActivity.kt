package com.example.catlifepet.privacy

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
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.catlifepet.R
import com.example.catlifepet.auth.AuthActivity
import com.example.catlifepet.chat.ChatActivity
import com.example.catlifepet.memory.MemoryActivity
import com.example.catlifepet.permission.OverlayPermissionHelper
import com.example.catlifepet.util.ScreenUtils

class PrivacyActivity : Activity() {
    private lateinit var content: LinearLayout

    private val background get() = ContextCompat.getColor(this, R.color.app_background)
    private val surface get() = ContextCompat.getColor(this, R.color.surface_primary)
    private val warmSurface get() = ContextCompat.getColor(this, R.color.surface_warm)
    private val primary get() = ContextCompat.getColor(this, R.color.primary)
    private val textPrimary get() = ContextCompat.getColor(this, R.color.text_primary)
    private val textSecondary get() = ContextCompat.getColor(this, R.color.text_secondary)
    private val danger get() = ContextCompat.getColor(this, R.color.danger_soft)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildPage())
        render()
    }

    private fun buildPage(): View {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(32))
        }
        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(this@PrivacyActivity.background)
            addView(content, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun render() {
        content.removeAllViews()
        topBar()
        content.addView(label("CatLifePet 会把本地桌宠能力和云端 AI 能力分开处理。未登录时，小猫悬浮、提醒、拖动、大小、勿扰等设置都只保存在这台设备上。", 14f, false, textSecondary), match(18))
        content.addView(section("权限说明"), match(6))
        content.addView(infoCard("悬浮窗权限", "用于把小猫显示在其他 App 上方。关闭权限后，小猫不会悬浮显示，但 App 内设置仍可使用。"), match(10))
        content.addView(infoCard("通知权限", "用于前台服务通知和生活提醒。Android 13 及以上可以在系统设置里单独关闭通知。"), match(16))
        content.addView(section("账号与云端数据"), match(6))
        content.addView(infoCard("账号数据", "登录仅用于 AI 对话、云端聊天记录和陪伴记忆。服务器保存邮箱、昵称、时区、刷新令牌哈希和必要的安全日志。"), match(10))
        content.addView(infoCard("聊天记录", "登录后的会话和消息会保存到服务器，也会在本机保留最近缓存，方便断网时查看。你可以删除单段聊天或清空全部云端聊天。"), match(10))
        content.addView(infoCard("陪伴记忆", "小猫只使用你明确保存或确认的长期记忆，例如昵称、作息和偏好。你可以随时查看、删除单条记忆或清空全部记忆。"), match(16))
        content.addView(section("AI 处理"), match(6))
        content.addView(infoCard("AI 请求", "发送消息时，服务器会把必要的最近聊天、会话摘要和陪伴记忆发送给 AI 服务生成回复。OpenAI API Key 只保存在服务器，不会进入 APK。"), match(10))
        content.addView(infoCard("安全与降级", "服务器会限制频率、每日用量和单次内容长度。网络或模型不可用时，桌宠和本地提醒仍然继续工作。"), match(16))
        content.addView(section("管理入口"), match(6))
        content.addView(actionCard("账号管理", "登录、退出、修改昵称或注销账号", "打开") {
            startActivity(Intent(this, AuthActivity::class.java))
        }, match(10))
        content.addView(actionCard("聊天记录", "继续历史会话，或删除当前聊天", "打开") {
            startActivity(Intent(this, ChatActivity::class.java))
        }, match(10))
        content.addView(actionCard("陪伴记忆", "查看、添加、删除或清空长期记忆", "打开") {
            startActivity(Intent(this, MemoryActivity::class.java))
        }, match(10))
        content.addView(actionCard("悬浮窗权限", overlayStatus(), "设置") {
            OverlayPermissionHelper.openOverlayPermissionSettings(this)
        }, match(16))
        content.addView(infoCard("注销账号会删除什么", "注销会删除云端账号、刷新令牌、聊天记录、陪伴记忆和相关云端资料。本地桌宠位置、大小、提醒开关等设备设置不会被云端注销影响。", danger), match(12))
    }

    private fun overlayStatus(): String =
        if (OverlayPermissionHelper.canDrawOverlays(this)) "当前已授权，可以显示桌宠" else "当前未授权，小猫无法悬浮显示"

    private fun topBar() {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(button("‹", "返回") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(label("隐私与数据", 22f, true, textPrimary).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(row, match(18))
    }

    private fun section(value: String) = label(value, 18f, true, textPrimary)

    private fun infoCard(title: String, body: String, titleColor: Int = textPrimary): LinearLayout =
        card(title, body, null, titleColor, null)

    private fun actionCard(title: String, body: String, action: String, onClick: () -> Unit): LinearLayout =
        card(title, body, action, textPrimary, onClick)

    private fun card(title: String, body: String, action: String?, titleColor: Int, onClick: (() -> Unit)?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            minimumHeight = dp(64)
            background = rounded(if (action == null) surface else warmSurface, 8)
            contentDescription = if (action == null) "$title，$body" else "$title，$body，$action"
            isClickable = onClick != null
            setOnClickListener { onClick?.invoke() }
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label(title, 15f, true, titleColor), LinearLayout.LayoutParams(-1, -2))
        copy.addView(label(body, 13f, false, textSecondary).apply { setPadding(0, dp(4), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) {
            row.addView(label(action, 13f, true, primary).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(56), -2))
        }
        return row
    }

    private fun button(value: String, description: String, action: () -> Unit) = Button(this).apply {
        text = value
        contentDescription = description
        textSize = if (value == "‹") 30f else 15f
        isAllCaps = false
        minHeight = dp(48)
        setTextColor(textPrimary)
        background = rounded(Color.TRANSPARENT, 8)
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

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun match(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun dp(value: Int) = ScreenUtils.dp(this, value)
}
