package com.example.catlifepet

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.catlifepet.data.SettingsRepository
import com.example.catlifepet.auth.AuthActivity
import com.example.catlifepet.auth.AuthGraph
import com.example.catlifepet.chat.ChatActivity
import com.example.catlifepet.memory.MemoryActivity
import com.example.catlifepet.privacy.PrivacyActivity
import com.example.catlifepet.floating.CatFloatingService
import com.example.catlifepet.floating.CatState
import com.example.catlifepet.floating.GrowthUnlockManager
import com.example.catlifepet.floating.PetStatusManager
import com.example.catlifepet.permission.OverlayPermissionHelper
import com.example.catlifepet.reminder.ReminderManager
import com.example.catlifepet.reminder.ReminderType
import com.example.catlifepet.util.ScreenUtils
import java.time.LocalDate
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var reminderManager: ReminderManager
    private lateinit var petStatusManager: PetStatusManager
    private lateinit var growthUnlockManager: GrowthUnlockManager
    private lateinit var content: FrameLayout
    private lateinit var navCompanion: TextView
    private lateinit var navReminder: TextView
    private lateinit var navSettings: TextView
    private lateinit var navBar: View
    private var screen = Screen.COMPANION
    private var onboardingStep = 1
    private var onboardingVisible = false
    private var actionBusy = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var transientToast: Toast? = null

    private val WARM_BACKGROUND get() = ContextCompat.getColor(this, R.color.app_background)
    private val SURFACE get() = ContextCompat.getColor(this, R.color.surface_primary)
    private val SURFACE_WARM get() = ContextCompat.getColor(this, R.color.surface_warm)
    private val PRIMARY get() = ContextCompat.getColor(this, R.color.primary)
    private val PRIMARY_LIGHT get() = ContextCompat.getColor(this, R.color.primary_light)
    private val TEXT_PRIMARY get() = ContextCompat.getColor(this, R.color.text_primary)
    private val TEXT_SECONDARY get() = ContextCompat.getColor(this, R.color.text_secondary)
    private val SUCCESS get() = ContextCompat.getColor(this, R.color.success_soft)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = WARM_BACKGROUND
        window.navigationBarColor = SURFACE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        settingsRepository = SettingsRepository(this)
        reminderManager = ReminderManager(this)
        // Migrate before PetStatusManager initializes default values into prefs.
        migrateLegacyUserIfNeeded()
        petStatusManager = PetStatusManager.getInstance(this)
        growthUnlockManager = GrowthUnlockManager(this)
        growthUnlockManager.syncWithAffection(petStatusManager.getAffectionLevel())
        onboardingVisible = !settingsRepository.isOnboardingCompleted()
        setContentView(buildShell())
        if (!onboardingVisible) requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (redirectToLoginIfNeeded()) return
        if (::settingsRepository.isInitialized && settingsRepository.getTemporaryHideUntil() in 1 until System.currentTimeMillis()) {
            settingsRepository.clearTemporaryHide()
            Log.d(TAG, "temporary hide marker expired; pet can be shown")
        }
        if (::content.isInitialized) render(screen)
    }

    override fun onBackPressed() {
        if (screen.isDetail) {
            screen = screen.parent!!
            render(screen)
        } else {
            super.onBackPressed()
        }
    }

    private fun buildShell(): View {
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(WARM_BACKGROUND) }
        content = FrameLayout(this)
        shell.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(SURFACE, 22)
        }
        navCompanion = navItem("⌂", "陪伴") { screen = Screen.COMPANION; render(screen) }
        navReminder = navItem("♟", "提醒") { screen = Screen.REMINDERS; render(screen) }
        navSettings = navItem("⚙", "设置") { screen = Screen.SETTINGS; render(screen) }
        nav.addView(navCompanion, weightParams())
        nav.addView(navReminder, weightParams())
        nav.addView(navSettings, weightParams())
        navBar = nav
        shell.addView(nav, LinearLayout.LayoutParams(-1, dp(74)))
        render(screen)
        return shell
    }

    private fun render(target: Screen) {
        if (!::content.isInitialized) return
        content.removeAllViews()
        if (onboardingVisible) {
            navBar.visibility = View.GONE
            content.addView(scroll(onboardingPage()))
            return
        }
        navBar.visibility = View.VISIBLE
        content.addView(scroll(page(target)))
        val selected = target.root
        navCompanion.setTextColor(if (selected == Screen.COMPANION) PRIMARY else TEXT_SECONDARY)
        navReminder.setTextColor(if (selected == Screen.REMINDERS) PRIMARY else TEXT_SECONDARY)
        navSettings.setTextColor(if (selected == Screen.SETTINGS) PRIMARY else TEXT_SECONDARY)
    }

    private fun onboardingPage(): View {
        val root = column(dp(24), dp(28))
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(text("CatLifePet", 26f, TEXT_PRIMARY, true).apply { gravity = Gravity.CENTER }, match(dp(12)))
        val image = ImageView(this).apply {
            setImageResource(R.drawable.cat_happy)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "CatLifePet 小猫"
        }
        root.addView(image, LinearLayout.LayoutParams(-1, dp(220)))
        when (onboardingStep) {
            1 -> {
                root.addView(text("欢迎来到 CatLifePet", 23f, TEXT_PRIMARY, true).apply { gravity = Gravity.CENTER }, match(dp(10)))
                root.addView(text("一只会陪伴你生活的小猫。\n它会在屏幕旁边陪着你，也会温柔提醒喝水、吃饭、休息和睡觉。", 15f, TEXT_SECONDARY, false).apply { gravity = Gravity.CENTER }, match(dp(24)))
            }
            2 -> {
                root.addView(text("让小猫来到你的屏幕旁边", 23f, TEXT_PRIMARY, true).apply { gravity = Gravity.CENTER }, match(dp(10)))
                root.addView(text("开启悬浮窗权限后，小猫就能陪你使用其他 App。这个权限随时可以在设置里关闭。", 15f, TEXT_SECONDARY, false).apply { gravity = Gravity.CENTER }, match(dp(16)))
                val granted = OverlayPermissionHelper.canDrawOverlays(this)
                root.addView(infoCard(if (granted) "✓ 准备好了" else "还没有开启权限", if (granted) "现在可以继续下一步" else "稍后也可以在“设置 > 权限与运行状态”中开启"), match(dp(12)))
                if (!granted) root.addView(configureActionButton(Button(this).apply { text = "开启悬浮窗权限"; setOnClickListener { OverlayPermissionHelper.openOverlayPermissionSettings(this@MainActivity) } }), match(dp(12)))
            }
            else -> {
                root.addView(text("一起照顾好生活", 23f, TEXT_PRIMARY, true).apply { gravity = Gravity.CENTER }, match(dp(10)))
                root.addView(text("小猫会在合适的时候提醒你：", 15f, TEXT_SECONDARY, false).apply { gravity = Gravity.CENTER }, match(dp(12)))
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf("💧\n喝水", "🍚\n吃饭", "☀\n休息", "☾\n睡觉").forEach { value -> row.addView(text(value, 14f, TEXT_PRIMARY, true).apply { gravity = Gravity.CENTER; background = rounded(SURFACE_WARM, 16); setPadding(dp(8), dp(10), dp(8), dp(10)) }, weightParams()) }
                root.addView(row, match(dp(18)))
                root.addView(text("所有提醒都可以在 App 里随时调整。", 14f, TEXT_SECONDARY, false).apply { gravity = Gravity.CENTER }, match(dp(20)))
            }
        }
        root.addView(configureActionButton(Button(this).apply {
            text = if (onboardingStep == 3) "开始陪伴" else "下一步"
            setOnClickListener { if (onboardingStep < 3) { onboardingStep++; render(screen) } else completeOnboarding() }
        }), match(dp(12)))
        root.addView(text("$onboardingStep / 3", 13f, TEXT_SECONDARY, false).apply { gravity = Gravity.CENTER }, match(dp(12)))
        return root
    }

    private fun completeOnboarding() {
        settingsRepository.setOnboardingCompleted(true)
        onboardingVisible = false
        screen = Screen.COMPANION
        requestNotificationPermissionIfNeeded()
        render(screen)
        toast("准备好了，接下来可以召唤小猫")
    }

    private fun migrateLegacyUserIfNeeded() {
        if (settingsRepository.isOnboardingCompleted() || !settingsRepository.hasExistingUserData()) return
        settingsRepository.setOnboardingCompleted(true)
        settingsRepository.setFirstPetSummonCompleted(true)
        Log.d(TAG, "legacy user detected; onboarding skipped")
    }

    private fun redirectToLoginIfNeeded(): Boolean {
        if (AuthGraph.repository(this).hasStoredSession()) return false
        startActivity(
            AuthActivity.requiredLoginIntent(this).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
        return true
    }

    private fun page(target: Screen): View {
        val root = column(dp(20), dp(18))
        when (target) {
            Screen.COMPANION -> companionPage(root)
            Screen.REMINDERS -> reminderPage(root)
            Screen.SETTINGS -> settingsPage(root)
            Screen.WATER, Screen.FOOD, Screen.REST, Screen.SLEEP -> reminderDetailPage(root, target)
            Screen.PET_SETTINGS -> petSettingsPage(root)
            Screen.DND_SETTINGS -> dndPage(root)
            Screen.PERMISSION -> permissionPage(root)
            Screen.ABOUT -> aboutPage(root)
            Screen.DEBUG -> debugPage(root)
        }
        return root
    }

    private fun companionPage(root: LinearLayout) {
        topBar(root, "CatLifePet", "⚙") { screen = Screen.SETTINGS; render(screen) }
        val hero = cardColumn(SURFACE_WARM, 22)
        hero.addView(text("今天也一起生活吧～", 16f, TEXT_PRIMARY, true), wrap())
        val image = ImageView(this).apply {
            setImageResource(com.example.catlifepet.R.drawable.cat_idle)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "CatLifePet 小猫"
        }
        hero.addView(image, LinearLayout.LayoutParams(-1, dp(190)))
        hero.addView(text("我会在这里陪着你哦～", 15f, TEXT_PRIMARY, false), wrap())
        root.addView(hero, match(dp(12)))

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        chip(chips, "⚡ ${energyLabel()}")
        chip(chips, "☻ ${moodLabel()}")
        chip(chips, "♥ ${relationshipName()}")
        root.addView(chips, match(dp(12)))

        root.addView(cardRow("✦", "和小猫聊聊", "登录后可以继续聊天并保存记录", "聊聊") {
            startActivity(Intent(this, ChatActivity::class.java))
        }, match(dp(12)))

        root.addView(section("我们的关系"), wrap())
        root.addView(cardRow("♥", relationshipName(), relationshipDescription(), null), match(dp(12)))
        root.addView(section("桌宠状态"), wrap())
        val running = isCatServiceRunning()
        val permission = OverlayPermissionHelper.canDrawOverlays(this)
        val hiddenUntil = settingsRepository.getTemporaryHideUntil()
        val hidden = running && hiddenUntil > System.currentTimeMillis()
        val status = when {
            !permission -> "小猫还不能出来"
            hidden -> "小猫暂时休息中"
            running -> "● 桌宠正在陪伴中"
            else -> "小猫正在等你"
        }
        val subtitle = when {
            !permission -> "开启悬浮窗权限后，我就可以陪在你身边啦"
            hidden -> "预计 ${remainingHideText(hiddenUntil)} 后回来"
            running -> "小猫会一直待在屏幕旁边"
            else -> "准备好开始今天的陪伴了吗？"
        }
        val action = when {
            !permission -> "开启权限"
            hidden -> "暂时休息中"
            running -> if (actionBusy) "正在让小猫休息…" else "停止桌宠"
            else -> if (actionBusy) "正在召唤小猫…" else "召唤小猫"
        }
        root.addView(cardRow("●", status, subtitle, action) {
            if (actionBusy || hidden) return@cardRow
            when {
                !permission -> OverlayPermissionHelper.openOverlayPermissionSettings(this)
                running -> stopPet()
                else -> startPet()
            }
        }, match(dp(12)))
        root.addView(section("今天的陪伴"), wrap())
        root.addView(cardRow("◷", companionTimeText(), "小猫已经陪你待了一会儿", "⌁"), match(dp(12)))
        root.addView(section("今天的生活提醒"), wrap())
        val reminderGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        reminderCard(row1, "💧", "喝水", ReminderType.WATER)
        reminderCard(row1, "🍚", "吃饭", ReminderType.FOOD)
        reminderCard(row2, "☀", "休息", ReminderType.REST)
        reminderCard(row2, "☾", "睡觉", ReminderType.SLEEP)
        reminderGrid.addView(row1, wrap())
        reminderGrid.addView(row2, wrap())
        root.addView(reminderGrid, match(dp(12)))
    }

    private fun reminderPage(root: LinearLayout) {
        centeredTitle(root, "生活提醒", "让小猫陪你一起照顾好自己。")
        val settings = settingsRepository.getSettings()
        if (!settings.waterReminderEnabled && !settings.foodReminderEnabled && !settings.restReminderEnabled && !settings.sleepReminderEnabled) {
            root.addView(infoCard("今天还没有开启生活提醒", "选择几个适合你的提醒，让小猫帮你一起照顾好自己吧～"), match(dp(12)))
            root.addView(configureActionButton(Button(this).apply { text = "开启喝水提醒"; setOnClickListener {
                settingsRepository.setReminderEnabled(SettingsRepository.ReminderSettingKey.WATER, true)
                runReminderSync(); render(Screen.REMINDERS)
            } }), match(dp(12)))
        }
        reminderLargeCard(root, Screen.WATER, "💧", "喝水提醒", waterSummary())
        reminderLargeCard(root, Screen.FOOD, "🍚", "吃饭提醒", foodSummary())
        reminderLargeCard(root, Screen.REST, "☀", "休息提醒", "每 60 分钟提醒休息一下")
        reminderLargeCard(root, Screen.SLEEP, "☾", "睡觉提醒", "每天 ${settingsRepository.getSettings().doNotDisturbStart}")
    }

    private fun reminderDetailPage(root: LinearLayout, target: Screen) {
        backBar(root, target.title)
        text("${target.title}会在合适的时候提醒你。", 14f, TEXT_SECONDARY, false).also { root.addView(it, match(dp(16))) }
        val type = target.reminderType ?: return
        val settings = settingsRepository.getSettings()
        val enabled = when (type) {
            ReminderType.WATER -> settings.waterReminderEnabled
            ReminderType.FOOD -> settings.foodReminderEnabled
            ReminderType.REST -> settings.restReminderEnabled
            ReminderType.SLEEP -> settings.sleepReminderEnabled
        }
        root.addView(settingCard("启用${target.title}", "开启后接收${target.title}", enabled) {
            settingsRepository.setReminderEnabled(type.settingKey, it); runReminderSync(); toast("${target.title}设置已更新")
        }, match(dp(12)))
        when (type) {
            ReminderType.WATER -> {
                root.addView(infoCard("提醒频率", waterSummary()), match(dp(12)))
                root.addView(infoCard("提醒时间范围", "开始时间 08:00\n结束时间 22:00"), match(dp(12)))
                root.addView(settingCard("每日首次提醒", "在每天的开始时间后开始提醒", true) { toast("每日首次提醒已${if (it) "开启" else "关闭"}") }, match(dp(12)))
                reminderIllustration(root, com.example.catlifepet.R.drawable.cat_drinking, "记得多喝水哦～")
            }
            ReminderType.FOOD -> {
                root.addView(infoCard("用餐时间设置", "早餐 08:00\n午餐 12:00\n晚餐 18:00"), match(dp(12)))
                root.addView(infoCard("提醒提前时间", "提前 15 分钟提醒"), match(dp(12)))
                reminderIllustration(root, com.example.catlifepet.R.drawable.cat_eating, "按时吃饭，身体会更舒服～")
            }
            ReminderType.REST -> {
                root.addView(infoCard("提醒间隔", "每 60 分钟提醒一次"), match(dp(12)))
                root.addView(infoCard("工作时间范围", "开始时间 09:00\n结束时间 18:00"), match(dp(12)))
                reminderIllustration(root, com.example.catlifepet.R.drawable.cat_stretching, "休息一下吧～")
            }
            ReminderType.SLEEP -> {
                root.addView(infoCard("睡觉时间", "每天 ${settings.doNotDisturbStart}"), match(dp(12)))
                root.addView(infoCard("提醒提前时间", "提前 30 分钟提醒"), match(dp(12)))
                reminderIllustration(root, com.example.catlifepet.R.drawable.cat_sleeping, "晚安好梦，小猫陪你～")
            }
        }
    }

    private fun settingsPage(root: LinearLayout) {
        centeredTitle(root, "设置", "把小猫调整成适合你的样子。")
        val authRepository = AuthGraph.repository(this)
        val accountSubtitle = authRepository.currentUser?.email
            ?: if (authRepository.hasStoredSession()) "已保存登录状态" else "登录后使用 AI 对话与云端记录"
        root.addView(cardRow("☁", "账号与云同步", accountSubtitle, "›") {
            startActivity(Intent(this, AuthActivity::class.java))
        }, match(dp(12)))
        root.addView(cardRow("☰", "聊天记录", "查看历史会话，继续或删除当前聊天", "›") {
            startActivity(Intent(this, ChatActivity::class.java))
        }, match(dp(12)))
        root.addView(cardRow("✦", "陪伴记忆", "查看、添加或删除小猫使用的长期记忆", "›") {
            startActivity(Intent(this, MemoryActivity::class.java))
        }, match(dp(12)))
        root.addView(cardRow("ⓘ", "隐私与数据", "了解权限、账号数据、AI 处理和删除入口", "›") {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }, match(dp(12)))
        settingsEntry(root, "🐱", "桌宠设置", "悬浮窗、行为、外观等", Screen.PET_SETTINGS)
        settingsEntry(root, "◔", "勿扰设置", "免打扰时段与提醒规则", Screen.DND_SETTINGS)
        settingsEntry(root, "ⓘ", "权限与运行状态", "查看权限和服务状态", Screen.PERMISSION)
        settingsEntry(root, "✦", "关于 CatLifePet", "版本信息与应用说明", Screen.ABOUT)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) settingsEntry(root, "⌘", "开发者 / Debug", "仅 Debug 模式可见", Screen.DEBUG)
    }

    private fun petSettingsPage(root: LinearLayout) {
        backBar(root, "桌宠设置")
        val settings = settingsRepository.getSettings()
        root.addView(settingCard("桌宠开关", if (isCatServiceRunning()) "开启后小猫会出现在屏幕上" else "桌宠当前未运行", isCatServiceRunning()) {
            if (it) startPet() else stopPet(Screen.PET_SETTINGS)
        }, match(dp(12)))
        root.addView(section("小猫大小"), wrap())
        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(80 to "小", 120 to "中", 160 to "大").forEach { (value, labelText) ->
            val button = configureActionButton(Button(this).apply { text = "$labelText  ${value}dp" }).apply { background = rounded(if (settings.petSizeDp == value) PRIMARY_LIGHT else SURFACE, 16); setOnClickListener {
                settingsRepository.savePetSize(value); CatFloatingService.updateSize(this@MainActivity); toast("小猫大小已更新"); render(Screen.PET_SETTINGS)
            } }
            sizeRow.addView(button, weightParams())
        }
        root.addView(sizeRow, wrap())
        root.addView(settingCard("自动贴边", "拖动结束后，小猫会靠在屏幕边缘", true) { toast("自动贴边已${if (it) "开启" else "关闭"}") }, match(dp(12)))
        root.addView(settingCard("互动效果", "点击小猫时显示陪伴反馈", true) { toast("互动效果已${if (it) "开启" else "关闭"}") }, match(dp(12)))
        root.addView(cardRow("◷", "隐藏桌宠 30 分钟", "暂时让小猫安静一会儿", if (actionBusy) "处理中…" else "隐藏") { hidePetTemporarily() }, match(dp(12)))
    }

    private fun dndPage(root: LinearLayout) {
        backBar(root, "勿扰设置")
        var settings = settingsRepository.getSettings()
        root.addView(settingCard("勿扰模式", "开启后减少生活提醒打扰", settings.doNotDisturbEnabled) {
            settingsRepository.setDoNotDisturbEnabled(it); runReminderSync(); toast("勿扰模式已${if (it) "开启" else "关闭"}")
        }, match(dp(12)))
        root.addView(section("勿扰时段"), wrap())
        root.addView(timeRow("开始时间", settings.doNotDisturbStart) { saveDndTime(true, it) }, match(dp(12)))
        root.addView(timeRow("结束时间", settings.doNotDisturbEnd) { saveDndTime(false, it) }, match(dp(12)))
        root.addView(infoCard("勿扰规则", "静默生活提醒\n小猫仍然会陪着你\n睡觉提醒按现有规则处理"), match(dp(12)))
        root.addView(cardRow("◷", "今天不提醒", "只暂停今天的生活提醒，小猫仍会陪伴你", if (isMuteTodayActive()) "已开启" else "开启") {
            settingsRepository.setMuteToday(!isMuteTodayActive(), LocalDate.now().toString()); runReminderSync(); render(Screen.DND_SETTINGS)
        }, match(dp(12)))
        root.addView(cardRow("◒", "隐藏桌宠 30 分钟", "暂时隐藏，不影响之后的陪伴", if (actionBusy) "处理中…" else "隐藏") { hidePetTemporarily() }, match(dp(12)))
    }

    private fun permissionPage(root: LinearLayout) {
        backBar(root, "权限与运行状态")
        root.addView(section("必要权限"), wrap())
        root.addView(statusCard("悬浮窗权限", if (OverlayPermissionHelper.canDrawOverlays(this)) "已授权" else "未授权", OverlayPermissionHelper.canDrawOverlays(this)) { OverlayPermissionHelper.openOverlayPermissionSettings(this) }, match(dp(12)))
        root.addView(statusCard("通知权限", if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "已授权" else "未授权", Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { requestNotificationPermissionIfNeeded() }, match(dp(12)))
        root.addView(section("运行状态"), wrap())
        root.addView(infoCard("桌宠服务", if (isCatServiceRunning()) "正在运行" else "未运行"), match(dp(12)))
        root.addView(infoCard("悬浮窗", if (isCatServiceRunning()) "已显示在屏幕上" else "未显示"), match(dp(12)))
        root.addView(infoCard("提醒服务", "正常运行"), match(dp(12)))
    }

    private fun aboutPage(root: LinearLayout) {
        backBar(root, "关于 CatLifePet")
        val logo = ImageView(this).apply { setImageResource(R.drawable.cat_idle); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "CatLifePet" }
        root.addView(logo, LinearLayout.LayoutParams(-1, dp(150)))
        root.addView(text("CatLifePet", 20f, TEXT_PRIMARY, true).apply { gravity = Gravity.CENTER }, match(dp(4)))
        root.addView(text("一只陪伴你生活的桌面小猫", 14f, TEXT_SECONDARY, false).apply { gravity = Gravity.CENTER }, match(dp(20)))
        root.addView(infoCard("关于 CatLifePet", "CatLifePet 希望用一只安静的小猫，陪你度过每天的生活。\n它会在你需要的时候提醒喝水、吃饭、休息和睡觉，也会在屏幕旁边安静陪着你。"), match(dp(12)))
        root.addView(infoCard("当前能力", "• 悬浮陪伴与拖动贴边\n• 本地生活提醒与勿扰设置\n• Energy、Mood、Affection 与陪伴成长\n• 登录后使用 AI 对话与云端聊天记录\n• 桌宠和提醒仍可完全离线使用"), match(dp(12)))
        root.addView(infoCard("版本", packageVersion()), match(dp(12)))
        root.addView(text("© 2026 CatLifePet", 12f, TEXT_SECONDARY, false).apply { gravity = Gravity.CENTER }, match(dp(12)))
    }

    private fun debugPage(root: LinearLayout) {
        backBar(root, "开发者 / Debug")
        root.addView(infoCard("仅在 Debug 模式可见", "这里的操作只用于测试，不影响正式用户界面"), match(dp(12)))
        val info = TextView(this).apply { setTextColor(TEXT_PRIMARY); textSize = 14f; setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(SURFACE, 18) }
        root.addView(info, match(dp(12)))
        fun refresh() {
            petStatusManager.refreshEnergyFromElapsedTime(); petStatusManager.refreshMoodFromElapsedTime(); petStatusManager.refreshCompanionshipTime(); growthUnlockManager.syncWithAffection(petStatusManager.getAffectionLevel())
            info.text = "Energy  ${petStatusManager.getEnergy()} / 100\nEnergy Level  ${petStatusManager.getEnergyLevel()}\nMood  ${petStatusManager.getMood()} / 100\nMood Level  ${petStatusManager.getMoodLevel()}\nAffection  ${petStatusManager.getAffection()} / 100\nAffection Level  ${petStatusManager.getAffectionLevel()}\nHighest Unlocked  ${growthUnlockManager.currentLevel()}\nPending Level Up  ${growthUnlockManager.getPendingLevelUp()?.newLevel ?: "None"}\nUnlocked Features  ${growthUnlockManager.unlockedFeatures().joinToString { it.name }}\nCompanion Time  ${companionTimeText()}\nService  ${if (isCatServiceRunning()) "Running" else "Stopped"}"
        }
        refresh()
        debugButtons(root, "Energy", { petStatusManager.changeEnergy(-10, PetStatusManager.EnergyChangeReason.DEBUG_SET) }, { petStatusManager.changeEnergy(10, PetStatusManager.EnergyChangeReason.DEBUG_SET) }, { petStatusManager.setEnergy(PetStatusManager.DEFAULT_ENERGY) }, ::refresh)
        debugButtons(root, "Mood", { petStatusManager.changeMood(-10, PetStatusManager.MoodChangeReason.DEBUG_SET) }, { petStatusManager.changeMood(10, PetStatusManager.MoodChangeReason.DEBUG_SET) }, { petStatusManager.setMood(PetStatusManager.DEFAULT_MOOD) }, ::refresh)
        debugButtons(root, "Affection", { petStatusManager.changeAffectionForDebug(-10) }, { petStatusManager.changeAffectionForDebug(10) }, { petStatusManager.setAffectionForDebug(PetStatusManager.DEFAULT_AFFECTION) }, ::refresh)
        root.addView(section("Growth 边界测试"), wrap())
        val boundary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(19, 20, 49, 50, 79, 80).forEach { value -> boundary.addView(configureActionButton(Button(this).apply { text = "Aff $value"; setOnClickListener { petStatusManager.setAffectionForDebug(value); growthUnlockManager.syncWithAffection(petStatusManager.getAffectionLevel()); refresh() } }), weightParams()) }
        root.addView(boundary, wrap())
        root.addView(cardRow("↻", "重置 Growth 解锁状态", "以当前关系等级作为历史最高等级", "重置") { growthUnlockManager.resetForDebug(petStatusManager.getAffectionLevel()); refresh() }, match(dp(12)))
        root.addView(cardRow("◷", "模拟陪伴 +60 分钟", "测试陪伴时间和 Affection 奖励", "执行") { petStatusManager.simulateCompanionshipForDebug(PetStatusManager.COMPANIONSHIP_REWARD_INTERVAL_MS); refresh() }, match(dp(12)))
        root.addView(cardRow("↺", "重置今日好感计数", "清除今日奖励计数", "重置") { petStatusManager.resetDailyAffectionCountersForDebug(); refresh() }, match(dp(12)))
        root.addView(cardRow("◎", "重置 Onboarding", "下次打开 App 时重新展示三步引导", "重置") { settingsRepository.setOnboardingCompleted(false); onboardingStep = 1; onboardingVisible = true; render(screen) }, match(dp(12)))
        root.addView(cardRow("★", "重置首次召唤", "下一次成功召唤小猫时显示欢迎气泡", "重置") { settingsRepository.setFirstPetSummonCompleted(false); toast("首次召唤反馈已重置") }, match(dp(12)))
        root.addView(section("提醒测试"), wrap())
        listOf(ReminderType.WATER, ReminderType.FOOD, ReminderType.REST, ReminderType.SLEEP).forEach { type -> root.addView(cardRow("●", "测试${type.label}提醒", "立即显示桌宠提醒", "测试") { sendTestReminder(type) }, match(dp(8))) }
        root.addView(section("自然行为测试"), wrap())
        root.addView(cardRow("✦", "测试眨眼", "逐帧资源可用；仅 Debug 触发，不改变状态数值", "测试") {
            sendTestBehavior(CatState.BLINKING)
        }, match(dp(8)))
        root.addView(cardRow("△", "测试打哈欠动画", "8 帧真实张嘴哈欠动作；仅 Debug 触发，不改变状态数值", "测试") {
            sendTestBehavior(CatState.YAWNING)
        }, match(dp(8)))
        root.addView(cardRow("✦", "测试舔爪动画", "10 帧真实自我清洁动作；仅 Debug 触发，不改变状态数值", "测试") {
            sendTestBehavior(CatState.LICKING)
        }, match(dp(8)))
        root.addView(section("关系行为测试"), wrap())
        listOf(
            CatFloatingService.ACTION_DEBUG_CURIOUS to "好奇关注动画",
            CatFloatingService.ACTION_DEBUG_PEEK to "贴边偷看",
            CatFloatingService.ACTION_DEBUG_CUDDLE to "亲近互动"
        ).forEach { (action, label) ->
            val description = when (action) {
                CatFloatingService.ACTION_DEBUG_CURIOUS -> "8 帧真实抬耳倾头动作；测试关注文案与状态回退"
                CatFloatingService.ACTION_DEBUG_CUDDLE -> "播放亲近动作与专属文案"
                else -> "使用当前 HighestUnlockedLevel；偷看需要先贴边"
            }
            root.addView(cardRow("♡", "测试$label", description, "测试") {
                sendTestRelationshipBehavior(action)
            }, match(dp(8)))
        }
        root.addView(infoCard("Relationship Behaviors", growthUnlockManager.unlockedFeatures().joinToString { it.name }))
    }

    private fun debugButtons(root: LinearLayout, title: String, minus: () -> Unit, plus: () -> Unit, reset: () -> Unit, refresh: () -> Unit) {
        root.addView(section(title), wrap())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("$title -10" to minus, "$title +10" to plus, "重置" to reset).forEach { (labelText, action) -> row.addView(configureActionButton(Button(this).apply { text = labelText; setOnClickListener { action(); refresh() } }), weightParams()) }
        root.addView(row, wrap())
    }

    private fun topBar(root: LinearLayout, title: String, action: String, onAction: () -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(title, 24f, TEXT_PRIMARY, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = action; contentDescription = "打开设置"; setAllCaps(false); background = rounded(Color.TRANSPARENT, 14); setOnClickListener { onAction() } }, LinearLayout.LayoutParams(dp(54), dp(48)))
        root.addView(row, match(dp(14)))
    }

    private fun centeredTitle(root: LinearLayout, title: String, subtitle: String) {
        root.addView(text(title, 24f, TEXT_PRIMARY, true).apply { gravity = Gravity.CENTER }, match(dp(5)))
        root.addView(text(subtitle, 13f, TEXT_SECONDARY, false).apply { gravity = Gravity.CENTER }, match(dp(20)))
    }

    private fun backBar(root: LinearLayout, title: String) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(Button(this).apply { text = "‹"; contentDescription = "返回"; textSize = 30f; setAllCaps(false); background = rounded(Color.TRANSPARENT, 14); setOnClickListener { screen = screen.parent!!; render(screen) } }, LinearLayout.LayoutParams(dp(52), dp(52)))
        row.addView(text(title, 22f, TEXT_PRIMARY, true), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, match(dp(10)))
    }

    private fun settingsEntry(root: LinearLayout, icon: String, title: String, subtitle: String, target: Screen) {
        root.addView(cardRow(icon, title, subtitle, "›") { screen = target; render(screen) }, match(dp(12)))
    }

    private fun reminderLargeCard(root: LinearLayout, target: Screen, icon: String, title: String, subtitle: String) {
        root.addView(cardRow(icon, title, subtitle, "›") { screen = target; render(screen) }, match(dp(12)))
    }

    private fun reminderCard(parent: LinearLayout, icon: String, title: String, type: ReminderType) {
        val card = cardRow(icon, title, reminderEnabled(type), null) { screen = Screen.fromReminder(type); render(screen) }
        val switch = Switch(this).apply {
            isChecked = reminderIsEnabled(type)
            contentDescription = "启用$title"
            setOnCheckedChangeListener { _, enabled ->
                settingsRepository.setReminderEnabled(type.settingKey, enabled)
                runReminderSync()
                toast("$title${if (enabled) "已开启" else "已关闭"}")
            }
        }
        card.addView(switch, LinearLayout.LayoutParams(dp(58), -2))
        parent.addView(card, weightParams())
    }

    private fun cardRow(icon: String, title: String, subtitle: String, action: String?, onClick: (() -> Unit)? = null): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            minimumHeight = dp(64)
            background = rounded(SURFACE, 18)
            contentDescription = if (action != null) "$title，$subtitle，$action" else "$title，$subtitle"
            isClickable = onClick != null
            if (onClick != null) foreground = selectableItemBackground()
            setOnClickListener { onClick?.invoke() }
        }
        card.addView(text(icon, 24f, PRIMARY, false), LinearLayout.LayoutParams(dp(42), -2))
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(title, 15f, TEXT_PRIMARY, true), wrap())
        copy.addView(text(subtitle, 12f, TEXT_SECONDARY, false).apply { setPadding(0, dp(4), 0, 0) }, wrap())
        card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) card.addView(text(action, 13f, if (action == "已开启") SUCCESS else PRIMARY, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(70), -2))
        return card
    }

    private fun settingCard(title: String, subtitle: String, checked: Boolean, onChanged: (Boolean) -> Unit): LinearLayout {
        val card = cardRow("", title, subtitle, null)
        val switch = Switch(this).apply { isChecked = checked; contentDescription = title; setOnCheckedChangeListener { _: CompoundButton, value -> onChanged(value) } }
        card.addView(switch, LinearLayout.LayoutParams(dp(58), -2))
        return card
    }

    private fun statusCard(title: String, value: String, granted: Boolean, onClick: () -> Unit): LinearLayout {
        return cardRow(if (granted) "✓" else "!", title, "权限用于${if (title.contains("悬浮")) "显示桌宠" else "生活提醒"}", value) { onClick() }
    }

    private fun infoCard(title: String, value: String): LinearLayout = cardRow("", title, value, null)

    private fun reminderIllustration(root: LinearLayout, resource: Int, message: String) {
        val card = cardColumn(SURFACE_WARM, 18)
        val image = ImageView(this).apply { setImageResource(resource); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = message }
        card.addView(image, LinearLayout.LayoutParams(-1, dp(130)))
        card.addView(text(message, 14f, TEXT_PRIMARY, true).apply { gravity = Gravity.CENTER }, match(dp(4)))
        root.addView(card, match(dp(12)))
    }

    private fun timeRow(title: String, value: String, onSelected: (String) -> Unit): LinearLayout {
        return cardRow("", title, "", value) {
            val parts = value.split(":"); val hour = parts.getOrNull(0)?.toIntOrNull() ?: 23; val minute = parts.getOrNull(1)?.toIntOrNull() ?: 30
            TimePickerDialog(this, { _, h, m -> onSelected(String.format(Locale.ROOT, "%02d:%02d", h, m)) }, hour, minute, true).show()
        }
    }

    private fun saveDndTime(start: Boolean, value: String) {
        val old = settingsRepository.getSettings(); settingsRepository.saveDoNotDisturbTime(if (start) value else old.doNotDisturbStart, if (start) old.doNotDisturbEnd else value); runReminderSync(); render(Screen.DND_SETTINGS)
    }

    private fun chip(parent: LinearLayout, value: String) { parent.addView(text(value, 12f, TEXT_PRIMARY, true).apply { gravity = Gravity.CENTER; background = rounded(PRIMARY_LIGHT, 14); setPadding(dp(10), dp(8), dp(10), dp(8)) }, weightParams()) }

    private fun navItem(icon: String, label: String, onClick: () -> Unit): TextView = text("$icon\n$label", 12f, TEXT_SECONDARY, true).apply { gravity = Gravity.CENTER; contentDescription = label; minHeight = dp(48); setOnClickListener { onClick() } }

    private fun section(value: String) = text(value, 18f, TEXT_PRIMARY, true).apply { setPadding(0, dp(10), 0, dp(4)) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setLineSpacing(0f, 1.15f) }
    private fun column(left: Int, top: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(left, top, left, dp(110)) }
    private fun cardColumn(color: Int, radius: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = rounded(color, radius) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun configureActionButton(button: Button): Button = button.apply {
        setAllCaps(false)
        setTextColor(TEXT_PRIMARY)
        minHeight = dp(48)
        stateListAnimator = null
        background = rounded(SURFACE, 16)
    }
    private fun selectableItemBackground(): Drawable? {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return ContextCompat.getDrawable(this, value.resourceId)
    }
    private fun scroll(view: View) = ScrollView(this).apply { isFillViewport = true; addView(view) }
    private fun match(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { if (bottom > 0) bottomMargin = bottom }
    private fun wrap() = LinearLayout.LayoutParams(-1, -2)
    private fun weightParams() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    private fun dp(value: Int) = ScreenUtils.dp(this, value)

    private fun startPet() {
        if (actionBusy) return
        if (!OverlayPermissionHelper.canDrawOverlays(this)) { toast("请先开启悬浮窗权限"); OverlayPermissionHelper.openOverlayPermissionSettings(this); return }
        actionBusy = true
        toast("正在召唤小猫…")
        render(screen)
        try {
            CatFloatingService.start(this)
            runReminderSync()
            mainHandler.postDelayed({
                actionBusy = false
                if (isCatServiceRunning()) {
                    if (!settingsRepository.isFirstPetSummonCompleted()) {
                        settingsRepository.setFirstPetSummonCompleted(true)
                        CatFloatingService.showFirstSummon(this)
                    }
                    toast("小猫出来啦")
                } else {
                    toast("小猫还没能出来，请检查悬浮窗权限")
                }
                render(Screen.COMPANION)
            }, 900L)
        } catch (error: SecurityException) {
            actionBusy = false
            Log.e(TAG, "failed to start floating service", error)
            toast("暂时无法召唤小猫，请检查悬浮窗权限")
            render(Screen.COMPANION)
        } catch (error: IllegalArgumentException) {
            actionBusy = false
            Log.e(TAG, "invalid floating service request", error)
            toast("小猫暂时无法出来，请稍后再试")
            render(Screen.COMPANION)
        }
    }

    private fun stopPet(target: Screen = Screen.COMPANION) {
        if (actionBusy) return
        actionBusy = true
        toast("正在让小猫休息…")
        render(screen)
        try { CatFloatingService.stop(this) } catch (error: Exception) {
            Log.e(TAG, "failed to stop floating service", error)
            toast("小猫暂时无法停止，请稍后再试")
        }
        mainHandler.postDelayed({ actionBusy = false; settingsRepository.clearTemporaryHide(); render(target) }, 700L)
    }

    private fun hidePetTemporarily() {
        if (actionBusy || !isCatServiceRunning()) { toast("请先召唤小猫"); return }
        actionBusy = true
        settingsRepository.saveTemporaryHideUntil(System.currentTimeMillis() + 30L * 60L * 1000L)
        try {
            CatFloatingService.hideTemporarily(this)
            Log.d(TAG, "pet hidden temporarily")
            toast("小猫去休息啦，30 分钟后回来～")
        } catch (error: Exception) {
            settingsRepository.clearTemporaryHide()
            Log.e(TAG, "failed to hide pet temporarily", error)
            toast("暂时无法隐藏小猫，请稍后再试")
        }
        mainHandler.postDelayed({ actionBusy = false; render(Screen.COMPANION) }, 700L)
    }

    private fun sendTestReminder(type: ReminderType) {
        if (!OverlayPermissionHelper.canDrawOverlays(this)) { toast("请先开启悬浮窗权限"); OverlayPermissionHelper.openOverlayPermissionSettings(this); return }
        try { CatFloatingService.showReminder(this, type); toast("已发送${type.label}测试提醒") } catch (error: Exception) { Log.e(TAG, "test reminder failed: $type", error); toast("提醒暂时无法发送，请检查悬浮窗权限") }
    }

    private fun sendTestBehavior(state: CatState) {
        if (!OverlayPermissionHelper.canDrawOverlays(this)) { toast("请先开启悬浮窗权限"); OverlayPermissionHelper.openOverlayPermissionSettings(this); return }
        try { CatFloatingService.showDebugBehavior(this, state); toast("已触发${state.debugLabel}") }
        catch (error: Exception) { Log.e(TAG, "debug behavior failed: $state", error); toast("行为暂时无法触发") }
    }

    private fun sendTestRelationshipBehavior(action: String) {
        if (!OverlayPermissionHelper.canDrawOverlays(this)) {
            toast("请先开启悬浮窗权限")
            OverlayPermissionHelper.openOverlayPermissionSettings(this)
            return
        }
        runCatching {
            CatFloatingService.showDebugRelationshipBehavior(this, action)
            Log.d(TAG, "relationship debug action=$action")
            toast("已触发关系行为测试")
        }.onFailure {
            Log.e(TAG, "relationship behavior failed: $action", it)
            toast("关系行为暂时无法触发")
        }
    }

    private fun isCatServiceRunning(): Boolean = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(100).any { it.service.className == CatFloatingService::class.java.name }
    private fun isMuteTodayActive(): Boolean { val s = settingsRepository.getSettings(); return s.muteTodayEnabled && s.muteTodayDate == LocalDate.now().toString() }
    private fun reminderIsEnabled(type: ReminderType): Boolean { val s = settingsRepository.getSettings(); return when (type) { ReminderType.WATER -> s.waterReminderEnabled; ReminderType.FOOD -> s.foodReminderEnabled; ReminderType.REST -> s.restReminderEnabled; ReminderType.SLEEP -> s.sleepReminderEnabled } }
    private fun reminderEnabled(type: ReminderType): String = if (reminderIsEnabled(type)) "已开启" else "已关闭"
    private fun waterSummary() = if (settingsRepository.getSettings().debugReminderEnabled) "每 1 分钟提醒一次" else "每隔 2 小时提醒一次"
    private fun foodSummary() = "早餐 08:00 · 午餐 12:00 · 晚餐 18:00"
    private fun companionTimeText(): String { val minutes = petStatusManager.getCompanionshipAccumulatedMs() / 60_000L; return if (minutes >= 60) "今天已经陪伴 ${minutes / 60} 小时 ${minutes % 60} 分钟" else "今天已经陪伴 $minutes 分钟" }
    private fun energyLabel() = when (petStatusManager.getEnergyLevel()) { PetStatusManager.EnergyLevel.HIGH -> "精力充足"; PetStatusManager.EnergyLevel.NORMAL -> "状态不错"; PetStatusManager.EnergyLevel.LOW -> "有点累了"; PetStatusManager.EnergyLevel.EXHAUSTED -> "困倦的" }
    private fun moodLabel() = when (petStatusManager.getMoodLevel()) { PetStatusManager.MoodLevel.HAPPY -> "心情很好"; PetStatusManager.MoodLevel.NORMAL -> "心情不错"; PetStatusManager.MoodLevel.LOW -> "有点安静"; PetStatusManager.MoodLevel.SAD -> "想静静待着" }
    private fun relationshipName() = when (growthUnlockManager.currentLevel()) { PetStatusManager.AffectionLevel.NEW -> "刚刚认识"; PetStatusManager.AffectionLevel.FAMILIAR -> "熟悉的伙伴"; PetStatusManager.AffectionLevel.CLOSE -> "亲密伙伴"; PetStatusManager.AffectionLevel.BONDED -> "最好的伙伴" }
    private fun relationshipDescription() = when (growthUnlockManager.currentLevel()) { PetStatusManager.AffectionLevel.NEW -> "我们才刚刚认识。"; PetStatusManager.AffectionLevel.FAMILIAR -> "小猫已经开始熟悉你的陪伴。"; PetStatusManager.AffectionLevel.CLOSE -> "你们已经是很亲近的伙伴。"; PetStatusManager.AffectionLevel.BONDED -> "你们已经建立了深厚的陪伴关系。" }
    private fun remainingHideText(until: Long): String {
        val minutes = ((until - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L).coerceAtLeast(1L)
        return "$minutes 分钟"
    }

    private fun packageVersion(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "未知" }.getOrDefault("未知")

    private fun runReminderSync() {
        runCatching { reminderManager.syncAll() }.onFailure { error -> Log.e(TAG, "reminder sync failed", error); toast("提醒设置暂时无法同步，请稍后再试") }
    }

    private fun toast(value: String) {
        transientToast?.cancel()
        transientToast = Toast.makeText(this, value, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
    }

    private enum class Screen(val title: String, val parent: Screen? = null, val reminderType: ReminderType? = null) {
        COMPANION("陪伴"), REMINDERS("提醒"), SETTINGS("设置"), WATER("喝水提醒", REMINDERS, ReminderType.WATER), FOOD("吃饭提醒", REMINDERS, ReminderType.FOOD), REST("休息提醒", REMINDERS, ReminderType.REST), SLEEP("睡觉提醒", REMINDERS, ReminderType.SLEEP), PET_SETTINGS("桌宠设置", SETTINGS), DND_SETTINGS("勿扰设置", SETTINGS), PERMISSION("权限与运行状态", SETTINGS), ABOUT("关于 CatLifePet", SETTINGS), DEBUG("开发者 / Debug", SETTINGS);
        val root: Screen get() = when (this) { COMPANION, REMINDERS, SETTINGS -> this; else -> parent!!.root }
        val isDetail: Boolean get() = root != this
        companion object { fun fromReminder(type: ReminderType) = when (type) { ReminderType.WATER -> WATER; ReminderType.FOOD -> FOOD; ReminderType.REST -> REST; ReminderType.SLEEP -> SLEEP } }
    }

}

private const val TAG = "CatLifePet"

private val ReminderType.label: String get() = when (this) { ReminderType.WATER -> "喝水"; ReminderType.FOOD -> "吃饭"; ReminderType.REST -> "休息"; ReminderType.SLEEP -> "睡觉" }
private val ReminderType.settingKey: SettingsRepository.ReminderSettingKey get() = when (this) { ReminderType.WATER -> SettingsRepository.ReminderSettingKey.WATER; ReminderType.FOOD -> SettingsRepository.ReminderSettingKey.FOOD; ReminderType.REST -> SettingsRepository.ReminderSettingKey.REST; ReminderType.SLEEP -> SettingsRepository.ReminderSettingKey.SLEEP }
private val CatState.debugLabel: String get() = when (this) { CatState.BLINKING -> "眨眼"; CatState.YAWNING -> "打哈欠"; CatState.LICKING -> "舔爪"; else -> name }
