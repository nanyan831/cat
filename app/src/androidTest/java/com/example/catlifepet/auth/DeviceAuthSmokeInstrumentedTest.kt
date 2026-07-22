package com.example.catlifepet.auth

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceAuthSmokeInstrumentedTest {
    @Test
    fun loginStoresEncryptedSessionAndLogoutClearsIt() {
        assumeTrue(
            "Run only with :server:runDeviceAuthServer and adb reverse configured.",
            InstrumentationRegistry.getArguments().getString("deviceAuthSmoke") == "true"
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AuthGraph.repository(context)
        repository.clearLocalSession()
        val store = AndroidKeystoreSessionStore(context)
        val email = "device-${UUID.randomUUID()}@example.com"

        ActivityScenario.launch(AuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                root.findEditText("邮箱地址").setText(email)
                root.findButton("发送验证码").performClick()
            }
            scenario.waitForText("输入验证码")

            scenario.onActivity { activity ->
                val root = activity.window.decorView
                root.findEditText("6 位验证码").setText(DEVICE_TEST_CODE)
                root.findButton("登录").performClick()
            }
            scenario.waitForText("账号已连接")
            assertNotNull(store.readRefreshToken())

            scenario.onActivity { activity ->
                activity.window.decorView.findButton("退出登录").performClick()
            }
            scenario.waitForText("发送验证码")
            assertNull(store.readRefreshToken())
        }
    }

    private fun ActivityScenario<AuthActivity>.waitForText(expected: String) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            var found = false
            onActivity { found = it.window.decorView.hasText(expected) }
            if (found) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        assertTrue("Timed out waiting for text: $expected", false)
    }

    private fun View.findEditText(hint: String): EditText {
        return findView { it is EditText && it.hint?.toString() == hint } as? EditText
            ?: error("EditText not found: $hint")
    }

    private fun View.findButton(text: String): Button {
        return findView { it is Button && it.text.toString() == text } as? Button
            ?: error("Button not found: $text")
    }

    private fun View.hasText(expected: String): Boolean {
        return findView { it is TextView && it.text.toString() == expected } != null
    }

    private fun View.findView(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findView(predicate)?.let { return it }
        }
        return null
    }

    private companion object {
        const val DEVICE_TEST_CODE = "424242"
        const val WAIT_TIMEOUT_MILLIS = 15_000L
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
