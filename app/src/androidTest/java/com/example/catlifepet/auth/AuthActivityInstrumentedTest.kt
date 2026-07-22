package com.example.catlifepet.auth

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthActivityInstrumentedTest {
    @Before
    fun clearSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AndroidKeystoreSessionStore(context).clear()
    }

    @Test
    fun loggedOutAccountPageOpensWithoutACloudConnection() {
        ActivityScenario.launch(AuthActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertTrue(root.containsText("账号与云同步"))
                assertTrue(root.containsText("发送验证码"))
                assertTrue(root.containsText("本地陪伴不受影响"))
            }
        }
    }
}

private fun View.containsText(expected: String): Boolean {
    if (this is TextView && text.toString() == expected) return true
    if (this !is ViewGroup) return false
    return (0 until childCount).any { getChildAt(it).containsText(expected) }
}
