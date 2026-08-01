package com.example.catlifepet.auth

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.catlifepet.MainActivity
import com.example.catlifepet.R
import com.example.catlifepet.util.ScreenUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZoneId

class AuthActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: AuthRepository
    private lateinit var content: LinearLayout
    private var page = Page.EMAIL
    private var email = ""
    private var busy = false
    private var errorMessage: String? = null
    private var requireLogin = false

    private val backgroundColor get() = ContextCompat.getColor(this, R.color.app_background)
    private val surfaceColor get() = ContextCompat.getColor(this, R.color.surface_primary)
    private val warmSurfaceColor get() = ContextCompat.getColor(this, R.color.surface_warm)
    private val primaryColor get() = ContextCompat.getColor(this, R.color.primary)
    private val textColor get() = ContextCompat.getColor(this, R.color.text_primary)
    private val secondaryTextColor get() = ContextCompat.getColor(this, R.color.text_secondary)
    private val dangerColor get() = ContextCompat.getColor(this, R.color.danger_soft)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = surfaceColor
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        repository = AuthGraph.repository(this)
        requireLogin = intent.getBooleanExtra(EXTRA_REQUIRE_LOGIN, false)
        setContentView(buildShell())

        repository.currentUser?.let {
            if (requireLogin) {
                openMainAndFinish()
            } else {
                page = Page.ACCOUNT
                render()
            }
        } ?: if (repository.hasStoredSession()) {
            restoreSession()
        } else {
            render()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (requireLogin) {
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    private fun buildShell(): View {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(40))
        }
        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)
            addView(content)
        }
    }

    private fun render() {
        content.removeAllViews()
        addTopBar()
        when (page) {
            Page.EMAIL -> renderEmailPage()
            Page.CODE -> renderCodePage()
            Page.ACCOUNT -> renderAccountPage()
            Page.RESTORE_ERROR -> renderRestoreErrorPage()
        }
    }

    private fun addTopBar() {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(actionButton("‹") { handleBackAction() }.apply { contentDescription = "返回" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(label("账号与云同步", 22f, true, textColor), LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row, match(dp(18)))
    }

    private fun renderEmailPage() {
        content.addView(label("登录后和小猫继续聊天", 20f, true, textColor), match(dp(8)))
        content.addView(
            label("请先登录账号，登录后才能进入 CatLifePet。", 14f, false, secondaryTextColor),
            match(dp(22))
        )
        val emailInput = input("邮箱地址", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply {
            setText(email)
        }
        content.addView(emailInput, match(dp(12)))
        errorMessage?.let { content.addView(errorLabel(it), match(dp(10))) }
        content.addView(primaryButton(if (busy) "正在发送…" else "发送验证码") {
            if (busy) return@primaryButton
            email = emailInput.text.toString().trim()
            if (email.isBlank()) {
                errorMessage = "请输入邮箱地址。"
                render()
            } else {
                requestCode()
            }
        }, match(dp(18)))
        addLocalFeatureNotice()
    }

    private fun renderCodePage() {
        content.addView(label("输入验证码", 20f, true, textColor), match(dp(8)))
        content.addView(label("验证码已发送到 $email，有效期 10 分钟。", 14f, false, secondaryTextColor), match(dp(20)))
        val codeInput = input("6 位验证码", InputType.TYPE_CLASS_NUMBER).apply {
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        content.addView(codeInput, match(dp(12)))
        errorMessage?.let { content.addView(errorLabel(it), match(dp(10))) }
        content.addView(primaryButton(if (busy) "正在登录…" else "登录") {
            if (busy) return@primaryButton
            val code = codeInput.text.toString().trim()
            if (code.length != 6) {
                errorMessage = "请输入 6 位验证码。"
                render()
            } else {
                verifyCode(code)
            }
        }, match(dp(10)))
        content.addView(actionButton("重新发送验证码") {
            if (!busy) requestCode()
        }, match(dp(6)))
        content.addView(actionButton("更换邮箱") {
            if (!busy) {
                page = Page.EMAIL
                errorMessage = null
                render()
            }
        }, match(dp(18)))
        addLocalFeatureNotice()
    }

    private fun renderAccountPage() {
        val user = repository.currentUser ?: run {
            restoreSession()
            return
        }
        content.addView(label("账号已连接", 20f, true, textColor), match(dp(14)))
        val accountCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(surfaceColor, 8)
        }
        accountCard.addView(label(user.displayName ?: "小猫的朋友", 17f, true, textColor), match(dp(5)))
        accountCard.addView(label(user.email, 14f, false, secondaryTextColor), match(dp(5)))
        accountCard.addView(label("时区：${user.timeZone}", 13f, false, secondaryTextColor), match())
        content.addView(accountCard, match(dp(18)))

        val nicknameInput = input("昵称", InputType.TYPE_CLASS_TEXT).apply { setText(user.displayName.orEmpty()) }
        content.addView(nicknameInput, match(dp(10)))
        errorMessage?.let { content.addView(errorLabel(it), match(dp(10))) }
        content.addView(primaryButton(if (busy) "正在保存…" else "保存昵称") {
            if (!busy) updateProfile(nicknameInput.text.toString())
        }, match(dp(14)))
        content.addView(actionButton("退出登录") { if (!busy) logout() }, match(dp(8)))
        content.addView(dangerButton("注销账号") { if (!busy) confirmDeleteAccount() }, match(dp(20)))
        addLocalFeatureNotice()
    }

    private fun renderRestoreErrorPage() {
        content.addView(label("暂时无法连接服务器", 20f, true, textColor), match(dp(8)))
        content.addView(
            label(errorMessage ?: "请检查网络后重试。已保存的登录凭据不会因断网被清除。", 14f, false, secondaryTextColor),
            match(dp(18))
        )
        if (busy) {
            content.addView(ProgressBar(this), LinearLayout.LayoutParams(-1, dp(48)))
        } else {
            content.addView(primaryButton("重试") { restoreSession() }, match(dp(10)))
            content.addView(actionButton("仅退出此设备") {
                repository.clearLocalSession()
                page = Page.EMAIL
                errorMessage = null
                render()
            }, match(dp(18)))
        }
        addLocalFeatureNotice()
    }

    private fun addLocalFeatureNotice() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(warmSurfaceColor, 8)
        }
        card.addView(label("登录保护已开启", 15f, true, textColor), match(dp(4)))
        card.addView(label("登录成功后才能进入桌宠、提醒和 AI 聊天功能。", 13f, false, secondaryTextColor), match())
        content.addView(card, match())
    }

    private fun requestCode() = runBusy {
        when (val outcome = repository.requestCode(email)) {
            is AuthOutcome.Success -> {
                page = Page.CODE
                errorMessage = null
            }
            is AuthOutcome.Failure -> errorMessage = messageFor(outcome)
        }
    }

    private fun verifyCode(code: String) = runBusy {
        when (val outcome = repository.verifyCode(email, code)) {
            is AuthOutcome.Success -> {
                if (requireLogin) {
                    openMainAndFinish()
                } else {
                    page = Page.ACCOUNT
                    errorMessage = null
                }
            }
            is AuthOutcome.Failure -> errorMessage = messageFor(outcome)
        }
    }

    private fun restoreSession() {
        page = Page.RESTORE_ERROR
        runBusy {
            when (val outcome = repository.restoreSession()) {
                is AuthOutcome.Success -> {
                    if (requireLogin) {
                        openMainAndFinish()
                    } else {
                        page = Page.ACCOUNT
                        errorMessage = null
                    }
                }
                is AuthOutcome.Failure -> {
                    if (outcome.code == "logged_out" || outcome.httpStatus == 401) {
                        page = Page.EMAIL
                    } else {
                        page = Page.RESTORE_ERROR
                    }
                    errorMessage = messageFor(outcome)
                }
            }
        }
    }

    private fun updateProfile(displayName: String) = runBusy {
        when (
            val outcome = repository.updateProfile(
                displayName.takeIf(String::isNotBlank),
                ZoneId.systemDefault().id
            )
        ) {
            is AuthOutcome.Success -> errorMessage = null
            is AuthOutcome.Failure -> errorMessage = messageFor(outcome)
        }
    }

    private fun logout() = runBusy {
        repository.logout()
        requireLogin = true
        page = Page.EMAIL
        email = ""
        errorMessage = null
    }

    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this)
            .setTitle("注销账号")
            .setMessage("聊天记录和云端陪伴记忆将被永久删除，本地桌宠设置不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认注销") { _, _ -> deleteAccount() }
            .show()
    }

    private fun deleteAccount() = runBusy {
        when (val outcome = repository.deleteAccount()) {
            is AuthOutcome.Success -> {
                requireLogin = true
                page = Page.EMAIL
                email = ""
                errorMessage = null
            }
            is AuthOutcome.Failure -> errorMessage = messageFor(outcome)
        }
    }

    private fun openMainAndFinish() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private fun handleBackAction() {
        if (requireLogin) {
            moveTaskToBack(true)
        } else {
            finish()
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        busy = true
        render()
        scope.launch {
            block()
            busy = false
            render()
        }
    }

    private fun messageFor(failure: AuthOutcome.Failure): String = when (failure.code) {
        "invalid_code" -> "验证码错误或已过期，请重新输入。"
        "code_attempts_exceeded" -> "尝试次数过多，请重新发送验证码。"
        "rate_limited" -> "操作有些频繁，请稍后再试。"
        "invalid_session", "session_replay_detected", "invalid_access_token", "logged_out" -> "登录已失效，请重新登录。"
        "network_error" -> "无法连接服务器，请检查网络后重试。"
        "secure_storage_error" -> "无法安全保存登录状态，请检查设备安全设置。"
        "invalid_request" -> "输入内容不正确，请检查后重试。"
        else -> if (failure.retryable) "服务器暂时不可用，请稍后重试。" else "操作没有完成，请稍后重试。"
    }

    private fun input(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        contentDescription = hintText
        inputType = type
        textSize = 16f
        setTextColor(textColor)
        setHintTextColor(secondaryTextColor)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surfaceColor, 8)
        minHeight = dp(52)
    }

    private fun primaryButton(textValue: String, action: () -> Unit) = actionButton(textValue, action).apply {
        setTextColor(Color.WHITE)
        background = rounded(primaryColor, 8)
        isEnabled = !busy
        alpha = if (busy) 0.65f else 1f
    }

    private fun dangerButton(textValue: String, action: () -> Unit) = actionButton(textValue, action).apply {
        setTextColor(dangerColor)
        background = rounded(surfaceColor, 8)
    }

    private fun actionButton(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue
        textSize = if (textValue == "‹") 30f else 15f
        contentDescription = textValue
        setTextColor(textColor)
        isAllCaps = false
        minHeight = dp(48)
        stateListAnimator = null
        background = rounded(surfaceColor, 8)
        setOnClickListener { action() }
    }

    private fun errorLabel(value: String) = label(value, 13f, false, dangerColor)

    private fun label(value: String, size: Float, bold: Boolean, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun match(bottomMargin: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        this.bottomMargin = bottomMargin
    }

    private fun dp(value: Int) = ScreenUtils.dp(this, value)

    private enum class Page { EMAIL, CODE, ACCOUNT, RESTORE_ERROR }

    companion object {
        private const val EXTRA_REQUIRE_LOGIN = "com.example.catlifepet.auth.extra.REQUIRE_LOGIN"

        fun requiredLoginIntent(context: Context): Intent {
            return Intent(context, AuthActivity::class.java).putExtra(EXTRA_REQUIRE_LOGIN, true)
        }
    }
}
