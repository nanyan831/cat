package com.example.catlifepet.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `fake SSE deltas build one assistant reply without duplicating user text`() = runTest(dispatcher) {
        val source = FakeChatSource()
        val viewModel = ChatViewModel(source)
        advanceUntilIdle()

        viewModel.send("今天有点累", "client-one")
        advanceUntilIdle()

        assertFalse(viewModel.state.value.generating)
        assertEquals(listOf("今天有点累", "我在听。"), viewModel.state.value.messages.map { it.content })
        assertEquals(1, source.sentClientIds.count { it == "client-one" })
        assertTrue(viewModel.state.value.assistantDraft.isEmpty())
    }

    @Test
    fun `rapid deltas are buffered before rendering draft text`() = runTest(dispatcher) {
        val source = FakeChatSource(hang = true)
        val viewModel = ChatViewModel(source)
        advanceUntilIdle()

        viewModel.send("慢一点说", "client-buffer")
        dispatcher.scheduler.runCurrent()

        assertTrue(viewModel.state.value.generating)
        assertEquals("", viewModel.state.value.assistantDraft)

        dispatcher.scheduler.advanceTimeBy(260)
        dispatcher.scheduler.runCurrent()

        assertEquals("开", viewModel.state.value.assistantDraft)
        viewModel.stopGenerating()
        advanceUntilIdle()
    }

    @Test
    fun `network interruption keeps user text and retry reuses client id`() = runTest(dispatcher) {
        val source = FakeChatSource(failFirst = true)
        val viewModel = ChatViewModel(source)
        advanceUntilIdle()

        viewModel.send("不要丢掉这句话", "client-retry")
        advanceUntilIdle()
        val failed = viewModel.state.value.messages.single()
        assertEquals("不要丢掉这句话", failed.content)
        assertEquals("failed", failed.status)

        viewModel.retry(failed)
        advanceUntilIdle()

        assertEquals(listOf("client-retry", "client-retry"), source.sentClientIds)
        assertEquals(listOf("不要丢掉这句话", "我在听。"), viewModel.state.value.messages.map { it.content })
    }

    @Test
    fun `recreating view model reads one cached pending message`() = runTest(dispatcher) {
        val source = FakeChatSource(failFirst = true)
        val first = ChatViewModel(source)
        advanceUntilIdle()
        first.send("旋转后还在", "client-rotation")
        advanceUntilIdle()

        val recreated = ChatViewModel(source)
        advanceUntilIdle()

        assertEquals(1, recreated.state.value.messages.count { it.clientMessageId == "client-rotation" })
    }

    @Test
    fun `stop cancels fake stream and leaves retryable user text`() = runTest(dispatcher) {
        val source = FakeChatSource(hang = true)
        val viewModel = ChatViewModel(source)
        advanceUntilIdle()
        viewModel.send("先停一下", "client-stop")
        dispatcher.scheduler.runCurrent()

        assertTrue(viewModel.state.value.generating)
        viewModel.stopGenerating()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.generating)
        assertEquals("先停一下", viewModel.state.value.messages.single().content)
        assertEquals("stopped", viewModel.state.value.messages.single().status)
    }
}

private class FakeChatSource(
    private var failFirst: Boolean = false,
    private val hang: Boolean = false
) : ChatDataSource {
    private val conversations = MutableStateFlow(
        listOf(ChatConversation("conversation-one", null, "2026-07-22T00:00:00Z", "2026-07-22T00:00:00Z"))
    )
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val sentClientIds = mutableListOf<String>()

    override fun observeConversations(): Flow<List<ChatConversation>> = conversations
    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = messages
    override suspend fun loadWorkspace(preferredConversationId: String?) = ChatLoadResult.Ready("conversation-one")
    override suspend fun createConversation(title: String?) = ChatLoadResult.Ready("conversation-one")
    override suspend fun selectConversation(conversationId: String) = ChatLoadResult.Ready(conversationId)
    override suspend fun deleteConversation(conversationId: String) = ChatLoadResult.Ready("conversation-one")

    override fun sendMessage(conversationId: String, content: String, clientMessageId: String): Flow<ChatSendEvent> = flow {
        sentClientIds += clientMessageId
        messages.value = listOf(
            ChatMessage(
                clientMessageId, conversationId, 1, "user", content, "sending",
                clientMessageId = clientMessageId, pending = true
            )
        )
        if (hang) {
            try {
                emit(ChatSendEvent.Delta("assistant-one", "开"))
                awaitCancellation()
            } finally {
                messages.value = listOf(messages.value.single().copy(status = "stopped"))
            }
        }
        if (failFirst) {
            failFirst = false
            messages.value = listOf(messages.value.single().copy(status = "failed", errorMessage = "网络中断"))
            emit(ChatSendEvent.Failure("network_error", "网络中断", true))
            return@flow
        }
        emit(ChatSendEvent.Delta("assistant-one", "我在"))
        emit(ChatSendEvent.Delta("assistant-one", "听。"))
        messages.value = listOf(
            messages.value.single().copy(status = "completed", pending = false),
            ChatMessage("assistant-one", conversationId, 2, "assistant", "我在听。", "completed")
        )
        emit(ChatSendEvent.Completed(messages.value.last()))
    }
}
