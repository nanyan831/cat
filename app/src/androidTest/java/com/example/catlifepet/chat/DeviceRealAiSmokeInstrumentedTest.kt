package com.example.catlifepet.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.catlifepet.auth.AuthGraph
import com.example.catlifepet.auth.AuthOutcome
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceRealAiSmokeInstrumentedTest {
    @Test
    fun loginAndReceiveAnyNonBlankAiReply() = runBlocking {
        assumeTrue(
            "Run only with :server:runDeviceAuthServer, a real provider key, and adb reverse configured.",
            InstrumentationRegistry.getArguments().getString("deviceRealAiSmoke") == "true"
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val auth = AuthGraph.repository(context)
        auth.clearLocalSession()

        val email = "real-ai-device-${UUID.randomUUID()}@example.com"
        assertTrue(auth.requestCode(email) is AuthOutcome.Success)
        assertTrue(auth.verifyCode(email, DEVICE_TEST_CODE) is AuthOutcome.Success)

        val chat = ChatGraph.repository(context)
        val workspace = chat.loadWorkspace()
        assertTrue(workspace is ChatLoadResult.Ready)
        val conversationId = (workspace as ChatLoadResult.Ready).conversationId
        val events = chat.sendMessage(
            conversationId = conversationId,
            content = "请用一句中文温柔地回复我：今天想测试真实 AI 聊天。",
            clientMessageId = UUID.randomUUID().toString()
        ).toList()

        val completed = events.filterIsInstance<ChatSendEvent.Completed>().singleOrNull()
        val deltas = events.filterIsInstance<ChatSendEvent.Delta>().joinToString("") { it.text }
        assertTrue("Expected at least one streaming delta or completed message.", deltas.isNotBlank() || completed != null)
        assertTrue("Expected a non-blank AI reply.", completed?.message?.content?.isNotBlank() == true)
    }

    private companion object {
        const val DEVICE_TEST_CODE = "424242"
    }
}
