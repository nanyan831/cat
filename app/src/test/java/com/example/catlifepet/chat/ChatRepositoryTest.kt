package com.example.catlifepet.chat

import com.example.catlifepet.auth.AccessTokenProvider
import com.example.catlifepet.auth.AuthApiFactory
import com.example.catlifepet.auth.AuthRepository
import com.example.catlifepet.auth.SessionStore
import com.example.catlifepet.chat.data.ChatDao
import com.example.catlifepet.chat.data.ConversationEntity
import com.example.catlifepet.chat.data.MessageEntity
import com.example.catlifepet.chat.data.PendingMessageEntity
import com.example.catlifepet.chat.network.ChatHttpClient
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var dao: MemoryChatDao
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer().apply { start() }
        dao = MemoryChatDao()
        dao.upsertConversation(
            ConversationEntity("conversation", null, "2026-07-22T00:00:00Z", "2026-07-22T00:00:00Z")
        )
        val gson = Gson()
        val tokens = AccessTokenProvider().apply { accessToken = "access-test" }
        val client = AuthApiFactory.createClient(tokens)
        repository = ChatRepository(
            AuthRepository(
                AuthApiFactory.create(server.url("/").toString(), tokens, gson, client),
                MemoryTokenStore(),
                tokens,
                "repository-test",
                gson
            ),
            ChatHttpClient(server.url("/").toString(), client, gson),
            dao,
            now = { 100L }
        )
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `fake SSE failure preserves pending text and retry reuses row`() = runBlocking {
        server.enqueue(sse("event: error\ndata: {\"code\":\"network_error\",\"error\":\"网络中断\",\"retryable\":true}\n\n"))

        val first = repository.sendMessage("conversation", "请保留", "client-one").toList()

        assertTrue(first.single() is ChatSendEvent.Failure)
        assertEquals("请保留", dao.listPending("conversation").single().content)
        assertEquals("failed", dao.listPending("conversation").single().state)

        server.enqueue(sse(successStream()))
        server.enqueue(json(messagesJson()))
        val retry = repository.sendMessage("conversation", "请保留", "client-one").toList()

        assertTrue(retry.last() is ChatSendEvent.Completed)
        assertTrue(dao.listPending("conversation").isEmpty())
        assertEquals(listOf("user", "assistant"), dao.listMessages("conversation").map { it.role })
        val sentBodies = listOf(server.takeRequest(), server.takeRequest()).map { it.body.readUtf8() }
        assertTrue(sentBodies.all { it.contains("client-one") })
    }

    @Test
    fun `daily quota response becomes a clear local message and preserves input`() = runBlocking {
        server.enqueue(json(
            """{"error":{"code":"daily_quota_exceeded","message":"limit"}}"""
        ).setResponseCode(429))

        val events = repository.sendMessage("conversation", "明天见", "quota-one").toList()

        val failure = events.single() as ChatSendEvent.Failure
        assertEquals("今天的聊天次数已经用完了，我们明天再继续吧。", failure.message)
        assertEquals("明天见", dao.listPending("conversation").single().content)
    }

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun successStream() = """
        event: delta
        data: {"messageId":"assistant","delta":"我在听。"}

        event: completed
        data: {"message":{"id":"assistant","sequenceNumber":2,"role":"assistant","content":"我在听。","status":"completed","replyToMessageId":"user","model":"fake","createdAt":"2026-07-22T00:00:01Z","updatedAt":"2026-07-22T00:00:01Z"}}

    """.trimIndent()

    private fun messagesJson() = """
        {"messages":[
          {"id":"user","sequenceNumber":1,"role":"user","content":"请保留","status":"completed","clientMessageId":"client-one","createdAt":"2026-07-22T00:00:00Z","updatedAt":"2026-07-22T00:00:00Z"},
          {"id":"assistant","sequenceNumber":2,"role":"assistant","content":"我在听。","status":"completed","replyToMessageId":"user","model":"fake","createdAt":"2026-07-22T00:00:01Z","updatedAt":"2026-07-22T00:00:01Z"}
        ]}
    """.trimIndent()
}

private class MemoryTokenStore : SessionStore {
    override fun readRefreshToken(): String? = null
    override fun writeRefreshToken(refreshToken: String) = Unit
    override fun clear() = Unit
}

private class MemoryChatDao : ChatDao {
    private val conversationMap = linkedMapOf<String, ConversationEntity>()
    private val messageMap = linkedMapOf<String, MessageEntity>()
    private val pendingMap = linkedMapOf<String, PendingMessageEntity>()
    private val conversationFlow = MutableStateFlow<List<ConversationEntity>>(emptyList())
    private val messageFlows = mutableMapOf<String, MutableStateFlow<List<MessageEntity>>>()
    private val pendingFlows = mutableMapOf<String, MutableStateFlow<List<PendingMessageEntity>>>()

    override suspend fun upsertConversations(conversations: List<ConversationEntity>) {
        conversations.forEach { conversationMap[it.id] = it }
        publishConversations()
    }
    override suspend fun upsertConversation(conversation: ConversationEntity) = upsertConversations(listOf(conversation))
    override suspend fun upsertMessages(messages: List<MessageEntity>) {
        messages.forEach { messageMap[it.id] = it }
        messages.map { it.conversationId }.distinct().forEach(::publishMessages)
    }
    override suspend fun upsertMessage(message: MessageEntity) = upsertMessages(listOf(message))
    override suspend fun upsertPending(message: PendingMessageEntity) {
        pendingMap[message.clientMessageId] = message
        publishPending(message.conversationId)
    }
    override fun observeConversations(): Flow<List<ConversationEntity>> = conversationFlow
    override suspend fun listConversations() = conversationMap.values.sortedByDescending { it.updatedAt }
    override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageFlows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }
    override suspend fun listMessages(conversationId: String) = messageMap.values
        .filter { it.conversationId == conversationId }.sortedBy { it.sequenceNumber }
    override fun observePending(conversationId: String): Flow<List<PendingMessageEntity>> =
        pendingFlows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }
    override suspend fun listPending(conversationId: String) = pendingMap.values
        .filter { it.conversationId == conversationId }.sortedBy { it.createdAtEpochMillis }
    override suspend fun deletePending(clientMessageId: String) {
        val conversationId = pendingMap.remove(clientMessageId)?.conversationId ?: return
        publishPending(conversationId)
    }
    override suspend fun deleteMessages(conversationId: String) {
        messageMap.entries.removeIf { it.value.conversationId == conversationId }
        publishMessages(conversationId)
    }
    override suspend fun deleteConversation(conversationId: String) {
        conversationMap.remove(conversationId); deleteMessages(conversationId)
        pendingMap.entries.removeIf { it.value.conversationId == conversationId }
        publishConversations(); publishPending(conversationId)
    }
    override suspend fun deleteConversationsNotIn(activeIds: List<String>) {
        conversationMap.keys.filter { it !in activeIds }.toList().forEach { deleteConversation(it) }
    }
    override suspend fun deleteAllConversations() {
        conversationMap.keys.toList().forEach { deleteConversation(it) }
    }

    private fun publishConversations() { conversationFlow.value = conversationMap.values.toList() }
    private fun publishMessages(id: String) {
        messageFlows.getOrPut(id) { MutableStateFlow(emptyList()) }.value =
            messageMap.values.filter { it.conversationId == id }.sortedBy { it.sequenceNumber }
    }
    private fun publishPending(id: String) {
        pendingFlows.getOrPut(id) { MutableStateFlow(emptyList()) }.value =
            pendingMap.values.filter { it.conversationId == id }.sortedBy { it.createdAtEpochMillis }
    }
}
