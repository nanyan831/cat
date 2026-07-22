package com.example.catlifepet.server.chat

import com.example.catlifepet.server.ai.AiMessage
import com.example.catlifepet.server.ai.AiProvider
import com.example.catlifepet.server.ai.AiProviderException
import com.example.catlifepet.server.ai.AiRequest
import com.example.catlifepet.server.ai.AiResult
import com.example.catlifepet.server.ai.AiRuntimeOverrides
import com.example.catlifepet.server.ai.AiStreamEvent
import com.example.catlifepet.server.ai.AiUsage
import com.example.catlifepet.server.auth.AuthRuntimeOverrides
import com.example.catlifepet.server.auth.AuthSessionResponse
import com.example.catlifepet.server.auth.EmailSender
import com.example.catlifepet.server.auth.LoginCodeEmail
import com.example.catlifepet.server.auth.RequestLoginCodeRequest
import com.example.catlifepet.server.auth.VerifyLoginCodeRequest
import com.example.catlifepet.server.config.AiSettings
import com.example.catlifepet.server.config.AuthSettings
import com.example.catlifepet.server.config.DatabaseSettings
import com.example.catlifepet.server.config.ServerSettings
import com.example.catlifepet.server.module
import com.example.catlifepet.server.memory.CreateMemoryRequest
import com.example.catlifepet.server.memory.MemoryListResponse
import com.example.catlifepet.server.memory.MemoryResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatIntegrationTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var databaseSettings: DatabaseSettings

    @BeforeAll
    fun startPostgres() {
        postgres = EmbeddedPostgres.builder().setRegisterShutdownHook(false).start()
        databaseSettings = DatabaseSettings(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres")
    }

    @AfterAll
    fun stopPostgres() = postgres.close()

    @Test
    fun `authenticated SSE chat persists in order replays idempotently and enforces ownership`() = testApplication {
        val provider = ScriptedProvider()
        val sender = TestEmailSender()
        application { module(settings(), AuthRuntimeOverrides(sender), AiRuntimeOverrides(provider)) }
        val client = jsonClient()
        val owner = client.login(sender, "owner")
        val stranger = client.login(sender, "stranger")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/conversations").status)
        val conversation = client.createConversation(owner.accessToken, "晚间聊天")
        val clientMessageId = UUID.randomUUID().toString()
        val first = client.stream(owner.accessToken, conversation.id, "今天有点累", clientMessageId)

        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = first.bodyAsText()
        assertTrue(firstBody.indexOf("event: delta") < firstBody.indexOf("event: completed"))
        assertTrue(firstBody.contains("我在听。今天有点累"))
        val history = client.get("/v1/conversations/${conversation.id}/messages") {
            bearerAuth(owner.accessToken)
        }.body<MessageListResponse>()
        assertEquals(listOf(1L, 2L), history.messages.map { it.sequenceNumber })
        assertEquals(listOf("user", "assistant"), history.messages.map { it.role })
        assertEquals(listOf("completed", "completed"), history.messages.map { it.status })

        val replay = client.stream(owner.accessToken, conversation.id, "今天有点累", clientMessageId)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertTrue(replay.bodyAsText().contains("event: completed"))
        assertEquals(1, provider.calls.get())
        assertEquals(2, client.get("/v1/conversations/${conversation.id}/messages") {
            bearerAuth(owner.accessToken)
        }.body<MessageListResponse>().messages.size)

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/conversations/${conversation.id}/messages") {
            bearerAuth(stranger.accessToken)
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/conversations/${conversation.id}") {
            bearerAuth(stranger.accessToken)
        }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/conversations/${conversation.id}") {
            bearerAuth(owner.accessToken)
        }.status)
        assertEquals(0, client.get("/v1/conversations") {
            bearerAuth(owner.accessToken)
        }.body<ConversationListResponse>().conversations.size)
    }

    @Test
    fun `failed turn can retry without duplicating the user or assistant row`() = testApplication {
        val provider = ScriptedProvider(failuresBeforeSuccess = 1)
        val sender = TestEmailSender()
        application { module(settings(), AuthRuntimeOverrides(sender), AiRuntimeOverrides(provider)) }
        val client = jsonClient()
        val session = client.login(sender, "retry")
        val conversation = client.createConversation(session.accessToken, null)
        val clientMessageId = UUID.randomUUID().toString()

        val failed = client.stream(session.accessToken, conversation.id, "再试一次", clientMessageId)
        assertTrue(failed.bodyAsText().contains("event: error"))
        val retry = client.stream(session.accessToken, conversation.id, "再试一次", clientMessageId)
        assertTrue(retry.bodyAsText().contains("event: completed"))

        val history = client.get("/v1/conversations/${conversation.id}/messages") {
            bearerAuth(session.accessToken)
        }.body<MessageListResponse>().messages
        assertEquals(2, history.size)
        assertEquals(listOf("completed", "completed"), history.map { it.status })
        assertEquals(2, provider.calls.get())
    }

    @Test
    fun `AI timeout becomes a retryable SSE error and failed persisted state`() = testApplication {
        val provider = ScriptedProvider(delayMillis = 1_000)
        val sender = TestEmailSender()
        application {
            module(
                settings(AiSettings(requestTimeout = Duration.ofMillis(50))),
                AuthRuntimeOverrides(sender),
                AiRuntimeOverrides(provider)
            )
        }
        val client = jsonClient()
        val session = client.login(sender, "timeout")
        val conversation = client.createConversation(session.accessToken, null)

        val body = client.stream(
            session.accessToken,
            conversation.id,
            "还在吗",
            UUID.randomUUID().toString()
        ).bodyAsText()

        assertTrue(body.contains("ai_timeout"))
        assertTrue(body.contains("\"retryable\":true"))
        val history = client.get("/v1/conversations/${conversation.id}/messages") {
            bearerAuth(session.accessToken)
        }.body<MessageListResponse>().messages
        assertEquals("failed", history.last().status)
    }

    @Test
    fun `memory is owner controlled and deletion removes it from later prompts`() = testApplication {
        val provider = ScriptedProvider()
        val sender = TestEmailSender()
        application { module(settings(), AuthRuntimeOverrides(sender), AiRuntimeOverrides(provider)) }
        val client = jsonClient()
        val owner = client.login(sender, "memory-owner")
        val stranger = client.login(sender, "memory-stranger")
        val conversation = client.createConversation(owner.accessToken, null)

        val memory = client.post("/v1/memories") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateMemoryRequest("nickname", "请叫我小雨"))
        }.body<MemoryResponse>()
        assertEquals(1, client.get("/v1/memories") {
            bearerAuth(owner.accessToken)
        }.body<MemoryListResponse>().memories.size)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/memories/${memory.id}") {
            bearerAuth(stranger.accessToken)
        }.status)

        client.stream(owner.accessToken, conversation.id, "你好", UUID.randomUUID().toString()).bodyAsText()
        assertTrue(provider.requests.last().instructions.contains("请叫我小雨"))
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/memories/${memory.id}") {
            bearerAuth(owner.accessToken)
        }.status)
        client.stream(owner.accessToken, conversation.id, "还记得吗", UUID.randomUUID().toString()).bodyAsText()
        assertFalse(provider.requests.last().instructions.contains("请叫我小雨"))
        assertEquals(0, client.get("/v1/memories") {
            bearerAuth(owner.accessToken)
        }.body<MemoryListResponse>().memories.size)

        repeat(2) { index ->
            client.post("/v1/memories") {
                bearerAuth(owner.accessToken)
                contentType(ContentType.Application.Json)
                setBody(CreateMemoryRequest("preference", "偏好 $index"))
            }
        }
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/memories") {
            bearerAuth(owner.accessToken)
        }.status)
        assertEquals(0, client.get("/v1/memories") {
            bearerAuth(owner.accessToken)
        }.body<MemoryListResponse>().memories.size)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/conversations") {
            bearerAuth(owner.accessToken)
        }.status)
        assertEquals(0, client.get("/v1/conversations") {
            bearerAuth(owner.accessToken)
        }.body<ConversationListResponse>().conversations.size)
    }

    private fun settings(ai: AiSettings = AiSettings()) = ServerSettings.forTest(
        database = databaseSettings,
        authSettings = AuthSettings(maximumIpRequestsPerWindow = 100),
        aiSettings = ai
    )

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false }) }
    }

    private suspend fun HttpClient.login(sender: TestEmailSender, prefix: String): AuthSessionResponse {
        val email = "$prefix-${UUID.randomUUID()}@example.com"
        post("/v1/auth/code/request") {
            contentType(ContentType.Application.Json)
            setBody(RequestLoginCodeRequest(email))
        }
        return post("/v1/auth/code/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyLoginCodeRequest(email, sender.codeFor(email), "chat-test"))
        }.body()
    }

    private suspend fun HttpClient.createConversation(token: String, title: String?): ConversationResponse {
        return post("/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest(title))
        }.body()
    }

    private suspend fun HttpClient.stream(token: String, conversationId: String, content: String, clientId: String) =
        post("/v1/conversations/$conversationId/messages/stream") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(content, clientId))
        }
}

private class TestEmailSender : EmailSender {
    private val messages = ConcurrentHashMap<String, LoginCodeEmail>()
    override suspend fun sendLoginCode(email: LoginCodeEmail) { messages[email.recipient] = email }
    fun codeFor(email: String) = assertNotNull(messages[email]).code
}

private class ScriptedProvider(
    private val failuresBeforeSuccess: Int = 0,
    private val delayMillis: Long = 0
) : AiProvider {
    val calls = AtomicInteger()
    val requests = CopyOnWriteArrayList<AiRequest>()

    override suspend fun generate(request: AiRequest): AiResult = result(request.messages)

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        requests += request
        val call = calls.incrementAndGet()
        if (call <= failuresBeforeSuccess) throw AiProviderException("ai_provider_unavailable", true)
        if (delayMillis > 0) delay(delayMillis)
        val result = result(request.messages)
        emit(AiStreamEvent.Delta("我在听。"))
        emit(AiStreamEvent.Delta(request.messages.last().content))
        emit(AiStreamEvent.Completed(result))
    }

    private fun result(messages: List<AiMessage>) = AiResult(
        providerResponseId = "test-${calls.get()}",
        text = "我在听。${messages.last().content}",
        model = "chat-test",
        usage = AiUsage(8, 6, 14)
    )
}
