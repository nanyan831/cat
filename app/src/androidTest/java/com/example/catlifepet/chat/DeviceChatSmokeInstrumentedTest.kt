package com.example.catlifepet.chat

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.catlifepet.auth.AuthGraph
import com.example.catlifepet.auth.AuthOutcome
import com.example.catlifepet.floating.CatFloatingService
import com.example.catlifepet.permission.OverlayPermissionHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceChatSmokeInstrumentedTest {
    @Test
    fun loginStreamAndOverlayContinueTogether() = runBlocking {
        assumeTrue(
            "Run only with :server:runDeviceAuthServer and adb reverse configured.",
            InstrumentationRegistry.getArguments().getString("deviceChatSmoke") == "true"
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val auth = AuthGraph.repository(context)
        auth.clearLocalSession()
        val email = "chat-device-${UUID.randomUUID()}@example.com"
        assertTrue(auth.requestCode(email) is AuthOutcome.Success)
        assertTrue(auth.verifyCode(email, DEVICE_TEST_CODE) is AuthOutcome.Success)

        var overlayExpected = false
        if (OverlayPermissionHelper.canDrawOverlays(context)) {
            overlayExpected = true
            ContextCompat.startForegroundService(
                context,
                Intent(context, CatFloatingService::class.java).setAction(CatFloatingService.ACTION_START)
            )
        }

        ActivityScenario.launch(ChatActivity::class.java).use { scenario ->
            scenario.waitUntil { it.window.decorView.findEditTextOrNull("想和小猫说点什么？")?.isEnabled == true }
            scenario.onActivity { activity ->
                activity.window.decorView.findEditText("想和小猫说点什么？").setText("真机聊天测试")
                activity.window.decorView.findButton("发送").performClick()
            }
            scenario.waitUntil { it.window.decorView.containsText("我在呢。") }
            scenario.waitUntil { it.window.decorView.findEditTextOrNull("想和小猫说点什么？")?.isEnabled == true }
            scenario.onActivity { activity ->
                activity.window.decorView.findEditText("想和小猫说点什么？").setText("我不想活了")
                activity.window.decorView.findButton("发送").performClick()
            }
            scenario.waitUntil { it.window.decorView.containsText("我很在意你现在的安全") }
            if (overlayExpected) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CatFloatingService::class.java).setAction(CatFloatingService.ACTION_SHOW_RANDOM_TALK)
                )
                Thread.sleep(500)
                assertTrue(context.isPetServiceRunning())
            }
        }
    }

    private fun ActivityScenario<ChatActivity>.waitUntil(predicate: (ChatActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            var matched = false
            onActivity { matched = predicate(it) }
            if (matched) return
            Thread.sleep(100)
        }
        assertTrue("Timed out waiting for chat state", false)
    }

    @Suppress("DEPRECATION")
    private fun Context.isPetServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == CatFloatingService::class.java.name
        }
    }

    private companion object { const val DEVICE_TEST_CODE = "424242" }
}

private fun View.findEditTextOrNull(hint: String): EditText? =
    findView { it is EditText && it.hint?.toString() == hint } as? EditText

private fun View.findEditText(hint: String): EditText = findEditTextOrNull(hint)
    ?: error("EditText not found: $hint")

private fun View.findButton(value: String): Button =
    findView { it is Button && it.text.toString() == value } as? Button
        ?: error("Button not found: $value")

private fun View.containsText(value: String): Boolean =
    findView { it is TextView && it.text.toString().contains(value) } != null

private fun View.findView(predicate: (View) -> Boolean): View? {
    if (predicate(this)) return this
    if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).findView(predicate)?.let { return it }
    return null
}
