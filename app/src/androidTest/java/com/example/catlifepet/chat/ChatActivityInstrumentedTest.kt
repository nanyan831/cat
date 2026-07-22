package com.example.catlifepet.chat

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.catlifepet.auth.AndroidKeystoreSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatActivityInstrumentedTest {
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AndroidKeystoreSessionStore(context).clear()
        ChatGraph.clearTestDataSource()
    }

    @After
    fun tearDown() { ChatGraph.clearTestDataSource() }

    @Test
    fun loggedOutChatShowsSessionActionWithoutACloudConnection() {
        ActivityScenario.launch(ChatActivity::class.java).use { scenario ->
            scenario.waitForText("去登录")
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.hasText("登录已失效，请重新登录。"))
            }
        }
    }

    @Test
    fun recreationDoesNotDuplicateSentMessage() {
        ChatGraph.testDataSource = InstrumentedFakeChatSource()
        ActivityScenario.launch(ChatActivity::class.java).use { scenario ->
            scenario.waitForText("小猫在这里，慢慢说就好。")
            scenario.onActivity { activity ->
                activity.window.decorView.findEditText("想和小猫说点什么？").setText("旋转测试")
                activity.window.decorView.findButton("发送").performClick()
            }
            scenario.waitForText("我在听。")
            scenario.recreate()
            scenario.waitForText("我在听。")
            scenario.onActivity { activity ->
                assertEquals(1, activity.window.decorView.countText("旋转测试"))
                assertEquals(1, activity.window.decorView.countText("我在听。"))
            }
        }
    }
}

private class InstrumentedFakeChatSource : ChatDataSource {
    private val conversations = MutableStateFlow(
        listOf(ChatConversation("instrumented", null, "2026-07-22T00:00:00Z", "2026-07-22T00:00:00Z"))
    )
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    override fun observeConversations(): Flow<List<ChatConversation>> = conversations
    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = messages
    override suspend fun loadWorkspace(preferredConversationId: String?) = ChatLoadResult.Ready("instrumented")
    override suspend fun createConversation(title: String?) = ChatLoadResult.Ready("instrumented")
    override suspend fun selectConversation(conversationId: String) = ChatLoadResult.Ready(conversationId)
    override suspend fun deleteConversation(conversationId: String) = ChatLoadResult.Ready("instrumented")
    override fun sendMessage(conversationId: String, content: String, clientMessageId: String): Flow<ChatSendEvent> = flow {
        messages.value = listOf(
            ChatMessage(clientMessageId, conversationId, 1, "user", content, "completed", clientMessageId)
        )
        emit(ChatSendEvent.Delta("assistant", "我在听。"))
        val assistant = ChatMessage("assistant", conversationId, 2, "assistant", "我在听。", "completed")
        messages.value = messages.value + assistant
        emit(ChatSendEvent.Completed(assistant))
    }
}

private fun ActivityScenario<ChatActivity>.waitForText(expected: String) {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
        var found = false
        onActivity { found = it.window.decorView.hasText(expected) }
        if (found) return
        Thread.sleep(100)
    }
    assertTrue("Timed out waiting for: $expected", false)
}

private fun View.findEditText(hint: String): EditText =
    findView { it is EditText && it.hint?.toString() == hint } as? EditText
        ?: error("EditText not found: $hint")

private fun View.findButton(value: String): Button =
    findView { it is Button && it.text.toString() == value } as? Button
        ?: error("Button not found: $value")

private fun View.hasText(value: String): Boolean = countText(value) > 0

private fun View.countText(value: String): Int {
    var count = if (this is TextView && text.toString() == value) 1 else 0
    if (this is ViewGroup) for (index in 0 until childCount) count += getChildAt(index).countText(value)
    return count
}

private fun View.findView(predicate: (View) -> Boolean): View? {
    if (predicate(this)) return this
    if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).findView(predicate)?.let { return it }
    return null
}
