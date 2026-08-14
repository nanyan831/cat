package com.example.catlifepet

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.catlifepet.auth.AndroidKeystoreSessionStore
import com.example.catlifepet.auth.AuthActivity
import com.example.catlifepet.chat.ChatActivity
import com.example.catlifepet.chat.ChatGraph
import com.example.catlifepet.data.SettingsRepository
import com.example.catlifepet.memory.MemoryActivity
import com.example.catlifepet.privacy.PrivacyActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityContractInstrumentedTest {
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AndroidKeystoreSessionStore(context).clear()
        SettingsRepository(context).setOnboardingCompleted(true)
        ChatGraph.clearTestDataSource()
    }

    @After
    fun tearDown() {
        ChatGraph.clearTestDataSource()
    }

    @Test
    fun primaryScreensExposeAccessibleClickableTargets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AndroidKeystoreSessionStore(context).writeRefreshToken("accessibility-test-refresh-token")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.clickFirstAccessibleNameContaining("设置"))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertTrue(root.containsText("AI 会话"))
                assertTrue(root.containsText("陪伴记忆"))
                assertTrue(root.containsText("隐私与数据"))
                root.assertClickableTargets(activity, "MainActivity")
            }
        }
        AndroidKeystoreSessionStore(context).clear()
        ActivityScenario.launch(AuthActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.decorView.assertClickableTargets(it, "AuthActivity") }
        }
        ActivityScenario.launch(ChatActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.decorView.assertClickableTargets(it, "ChatActivity") }
        }
        ActivityScenario.launch(MemoryActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.decorView.assertClickableTargets(it, "MemoryActivity") }
        }
        ActivityScenario.launch(PrivacyActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertTrue(root.containsText("隐私与数据"))
                assertTrue(root.containsText("AI 请求"))
                root.assertClickableTargets(activity, "PrivacyActivity")
            }
        }
    }
}

private fun View.assertClickableTargets(context: Context, owner: String) {
    if (visibility != View.VISIBLE) return
    if (isClickable) {
        val min = dp(context, 48)
        assertTrue("$owner clickable view is too narrow: ${describe()}", width >= min)
        assertTrue("$owner clickable view is too short: ${describe()}", height >= min)
        assertTrue("$owner clickable view has no label: ${describe()}", accessibleName().isNotBlank())
    }
    if (this is ViewGroup) {
        for (index in 0 until childCount) getChildAt(index).assertClickableTargets(context, owner)
    }
}

private fun View.accessibleName(): String {
    contentDescription?.toString()?.takeIf(String::isNotBlank)?.let { return it }
    if (this is TextView) text?.toString()?.takeIf(String::isNotBlank)?.let { return it }
    if (this is ViewGroup) {
        val childText = buildString {
            for (index in 0 until childCount) {
                val name = getChildAt(index).accessibleName()
                if (name.isNotBlank()) append(name)
            }
        }
        if (childText.isNotBlank()) return childText
    }
    return ""
}

private fun View.containsText(expected: String): Boolean {
    if (this is TextView && text.toString() == expected) return true
    if (this is ViewGroup) {
        for (index in 0 until childCount) if (getChildAt(index).containsText(expected)) return true
    }
    return false
}

private fun View.clickFirstAccessibleNameContaining(expected: String): Boolean {
    if (visibility != View.VISIBLE) return false
    if (isClickable && accessibleName().contains(expected)) {
        performClick()
        return true
    }
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            if (getChildAt(index).clickFirstAccessibleNameContaining(expected)) return true
        }
    }
    return false
}

private fun View.describe(): String =
    "${javaClass.simpleName}(text=${(this as? TextView)?.text}, contentDescription=$contentDescription, width=$width, height=$height)"

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()
