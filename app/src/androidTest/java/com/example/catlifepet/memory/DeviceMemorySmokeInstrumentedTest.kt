package com.example.catlifepet.memory

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
import com.example.catlifepet.auth.AuthGraph
import com.example.catlifepet.auth.AuthOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceMemorySmokeInstrumentedTest {
    @Test
    fun loginCreateListAndClearMemory() = runBlocking {
        assumeTrue(
            "Run only with :server:runDeviceAuthServer and adb reverse configured.",
            InstrumentationRegistry.getArguments().getString("deviceMemorySmoke") == "true"
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val auth = AuthGraph.repository(context)
        auth.clearLocalSession()
        val email = "memory-device-${UUID.randomUUID()}@example.com"
        assertTrue(auth.requestCode(email) is AuthOutcome.Success)
        assertTrue(auth.verifyCode(email, "424242") is AuthOutcome.Success)

        ActivityScenario.launch(MemoryActivity::class.java).use { scenario ->
            scenario.waitForDeviceMemoryText("还没有保存陪伴记忆。")
            scenario.onActivity { activity ->
                activity.window.decorView.deviceMemoryEditText("例如：请叫我小雨").setText("真机记忆小雨")
                activity.window.decorView.deviceMemoryButton("保存这条记忆").performClick()
            }
            scenario.waitForDeviceMemoryText("已保存 1 条记忆")
            assertTrue(MemoryGraph.source(context).deleteAll() is MemoryOutcome.Success)
            scenario.recreate()
            scenario.waitForDeviceMemoryText("还没有保存陪伴记忆。")
        }
    }
}

private fun ActivityScenario<MemoryActivity>.waitForDeviceMemoryText(expected: String) {
    val deadline = System.currentTimeMillis() + 20_000L
    while (System.currentTimeMillis() < deadline) {
        var found = false
        onActivity { found = it.window.decorView.deviceMemoryContainsText(expected) }
        if (found) return
        Thread.sleep(100)
    }
    assertTrue("Timed out waiting for: $expected", false)
}

private fun View.deviceMemoryEditText(hint: String): EditText =
    deviceMemoryFind { it is EditText && it.hint?.toString() == hint } as? EditText
        ?: error("EditText not found: $hint")

private fun View.deviceMemoryButton(value: String): Button =
    deviceMemoryFind { it is Button && it.text.toString() == value } as? Button
        ?: error("Button not found: $value")

private fun View.deviceMemoryContainsText(value: String): Boolean =
    deviceMemoryFind { it is TextView && it.text.toString().contains(value) } != null

private fun View.deviceMemoryFind(predicate: (View) -> Boolean): View? {
    if (predicate(this)) return this
    if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).deviceMemoryFind(predicate)?.let { return it }
    return null
}
