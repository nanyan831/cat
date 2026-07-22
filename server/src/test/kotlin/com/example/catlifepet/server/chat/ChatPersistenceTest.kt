package com.example.catlifepet.server.chat

import com.example.catlifepet.server.ai.AiGateway
import com.example.catlifepet.server.ai.AiProvider
import com.example.catlifepet.server.ai.AiRequest
import com.example.catlifepet.server.ai.AiResult
import com.example.catlifepet.server.ai.AiStreamEvent
import com.example.catlifepet.server.config.AiSettings
import com.example.catlifepet.server.config.DatabaseSettings
import com.example.catlifepet.server.data.ConversationRecord
import com.example.catlifepet.server.data.DatabaseFactory
import com.example.catlifepet.server.data.MessageRecord
import com.example.catlifepet.server.data.MessageRole
import com.example.catlifepet.server.data.MessageStatus
import com.example.catlifepet.server.data.Repositories
import com.example.catlifepet.server.data.UserRecord
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteRecursively
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatPersistenceTest {
    @Test
    fun `downstream cancellation cancels generation and persists cancelled reply`() = runBlocking {
        EmbeddedPostgres.builder().setRegisterShutdownHook(false).start().use { postgres ->
            val database = open(postgres)
            try {
                database.migrate()
                val repositories = Repositories()
                val user = user("cancel")
                database.transaction { repositories.users.insert(it, user) }
                val provider = HangingProvider()
                val service = ChatService(database, repositories, AiGateway(AiSettings(), provider))
                val conversation = service.createConversation(user.id, CreateConversationRequest(null))
                val turn = service.prepareTurn(
                    user.id,
                    UUID.fromString(conversation.id),
                    SendMessageRequest("停止生成", UUID.randomUUID().toString())
                )

                assertEquals(1, service.streamTurn(turn).take(1).toList().size)

                val messages = database.transaction {
                    repositories.messages.listForConversation(it, UUID.fromString(conversation.id))
                }
                assertEquals(MessageStatus.CANCELLED, messages.last().status)
                assertTrue(provider.cancelled.get())
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun `assistant finalization is compare-and-set and cannot complete twice`() {
        EmbeddedPostgres.builder().setRegisterShutdownHook(false).start().use { postgres ->
            val database = open(postgres)
            try {
                database.migrate()
                val repositories = Repositories()
                val now = Instant.now()
                val user = user("finalize")
                val conversation = ConversationRecord(UUID.randomUUID(), user.id, null, null, now, now)
                val userMessage = message(conversation.id, 1, MessageRole.USER, MessageStatus.COMPLETED, now)
                val assistant = message(
                    conversation.id,
                    2,
                    MessageRole.ASSISTANT,
                    MessageStatus.STREAMING,
                    now,
                    replyTo = userMessage.id
                )
                database.transaction {
                    repositories.users.insert(it, user)
                    repositories.conversations.insert(it, conversation)
                    repositories.messages.insert(it, userMessage)
                    repositories.messages.insert(it, assistant)
                }

                val first = database.transaction {
                    repositories.messages.completeIfStreaming(it, assistant.id, "完成", "test", 3, 2, now)
                }
                val second = database.transaction {
                    repositories.messages.completeIfStreaming(it, assistant.id, "重复", "test", 3, 2, now)
                }

                assertTrue(first)
                assertFalse(second)
                assertEquals("完成", database.transaction { repositories.messages.findById(it, assistant.id) }!!.content)
            } finally {
                database.close()
            }
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `PostgreSQL engine restart preserves conversation history`() {
        val dataDirectory = Files.createTempDirectory("catlifepet-postgres-restart-")
        val user = user("restart")
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val conversation = ConversationRecord(UUID.randomUUID(), user.id, "保留记录", null, now, now)
        val stored = message(conversation.id, 1, MessageRole.USER, MessageStatus.COMPLETED, now)
        try {
            startPersistentPostgres(dataDirectory).use { firstPostgres ->
                open(firstPostgres).use { database ->
                    database.migrate()
                    val repositories = Repositories()
                    database.transaction {
                        repositories.users.insert(it, user)
                        repositories.conversations.insert(it, conversation)
                        repositories.messages.insert(it, stored)
                    }
                }
            }

            startPersistentPostgres(dataDirectory).use { restartedPostgres ->
                open(restartedPostgres).use { database ->
                    assertEquals(0, database.migrate())
                    val repositories = Repositories()
                    val loaded = database.transaction {
                        repositories.messages.listForConversation(it, conversation.id)
                    }
                    assertEquals(listOf(stored), loaded)
                }
            }
        } finally {
            dataDirectory.deleteRecursively()
        }
    }

    private fun open(postgres: EmbeddedPostgres) = DatabaseFactory.open(
        DatabaseSettings(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres"),
        maximumPoolSize = 2
    )

    private fun startPersistentPostgres(path: java.nio.file.Path) = EmbeddedPostgres.builder()
        .setRegisterShutdownHook(false)
        .setCleanDataDirectory(false)
        .setDataDirectory(path)
        .start()

    private fun user(prefix: String): UserRecord {
        val now = Instant.now()
        return UserRecord(
            UUID.randomUUID(), "$prefix-${UUID.randomUUID()}@example.com",
            "$prefix@example.com", null, "UTC", now, now
        )
    }

    private fun message(
        conversationId: UUID,
        sequence: Long,
        role: MessageRole,
        status: MessageStatus,
        now: Instant,
        replyTo: UUID? = null
    ) = MessageRecord(
        UUID.randomUUID(), conversationId, sequence, role,
        if (role == MessageRole.USER) "你好" else "", status,
        if (role == MessageRole.USER) UUID.randomUUID() else null,
        replyTo, null, 0, 0, now, now
    )
}

private class HangingProvider : AiProvider {
    val cancelled = AtomicBoolean()
    override suspend fun generate(request: AiRequest): AiResult = awaitCancellation()
    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        try {
            emit(AiStreamEvent.Delta("开始"))
            awaitCancellation()
        } finally {
            cancelled.set(true)
        }
    }
}
