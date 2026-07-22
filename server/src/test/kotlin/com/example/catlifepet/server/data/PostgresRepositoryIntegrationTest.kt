package com.example.catlifepet.server.data

import com.example.catlifepet.server.config.DatabaseSettings
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
class PostgresRepositoryIntegrationTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var database: DatabaseFactory
    private val repositories = Repositories()

    @BeforeAll
    fun startPostgres() {
        postgres = EmbeddedPostgres.builder()
            .setRegisterShutdownHook(false)
            .start()
        database = DatabaseFactory.open(
            DatabaseSettings(
                jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
                user = "postgres",
                password = "postgres"
            ),
            maximumPoolSize = 2
        )
    }

    @AfterAll
    fun stopPostgres() {
        database.close()
        postgres.close()
    }

    @Test
    @Order(1)
    fun `fresh migrations apply once and repeat idempotently`() {
        assertEquals(3, database.migrate())
        assertEquals(0, database.migrate())

        val tables = database.transaction { connection ->
            connection.prepareStatement(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildSet { while (result.next()) add(result.getString("table_name")) }
                }
            }
        }

        assertTrue(
            tables.containsAll(
                setOf(
                    "users",
                    "login_codes",
                    "refresh_sessions",
                    "conversations",
                    "messages",
                    "memories",
                    "daily_usage",
                    "flyway_schema_history"
                )
            )
        )
    }

    @Test
    @Order(2)
    fun `repositories round trip identity chat memory and usage records`() {
        val now = Instant.parse("2026-07-22T05:30:00Z")
        val user = UserRecord(
            id = UUID.randomUUID(),
            emailNormalized = "cat@example.com",
            emailDisplay = "Cat@example.com",
            displayName = "Cat Friend",
            timeZone = "Asia/Shanghai",
            createdAt = now,
            updatedAt = now
        )
        val code = LoginCodeRecord(
            id = UUID.randomUUID(),
            emailNormalized = user.emailNormalized,
            codeHash = byteArrayOf(1, 2, 3),
            requestIpHash = byteArrayOf(4, 5, 6),
            expiresAt = now.plusSeconds(600),
            consumedAt = null,
            failedAttempts = 0,
            createdAt = now
        )
        val session = RefreshSessionRecord(
            id = UUID.randomUUID(),
            userId = user.id,
            familyId = UUID.randomUUID(),
            tokenHash = byteArrayOf(7, 8, 9),
            deviceLabel = "Android test",
            expiresAt = now.plusSeconds(86_400),
            revokedAt = null,
            replacedBy = null,
            createdAt = now,
            lastUsedAt = now
        )
        val conversation = ConversationRecord(
            id = UUID.randomUUID(),
            userId = user.id,
            title = "Evening chat",
            summary = null,
            createdAt = now,
            updatedAt = now
        )
        val userMessage = MessageRecord(
            id = UUID.randomUUID(),
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = "Today was busy.",
            status = MessageStatus.COMPLETED,
            clientMessageId = UUID.randomUUID(),
            model = null,
            inputTokens = 0,
            outputTokens = 0,
            createdAt = now,
            updatedAt = now
        )
        val assistantMessage = MessageRecord(
            id = UUID.randomUUID(),
            conversationId = conversation.id,
            role = MessageRole.ASSISTANT,
            content = "Let us take it slowly.",
            status = MessageStatus.COMPLETED,
            clientMessageId = null,
            model = "test-model",
            inputTokens = 12,
            outputTokens = 8,
            createdAt = now.plusSeconds(1),
            updatedAt = now.plusSeconds(1)
        )
        val memory = MemoryRecord(
            id = UUID.randomUUID(),
            userId = user.id,
            kind = "preference",
            content = "Prefers gentle reminders",
            sourceConversationId = conversation.id,
            confidence = 0.875,
            createdAt = now,
            updatedAt = now
        )

        database.transaction { connection ->
            repositories.users.insert(connection, user)
            repositories.loginCodes.insert(connection, code)
            repositories.refreshSessions.insert(connection, session)
            repositories.conversations.insert(connection, conversation)
            repositories.messages.insert(connection, userMessage)
            repositories.messages.insert(connection, assistantMessage)
            repositories.memories.insert(connection, memory)
        }

        database.transaction { connection ->
            assertEquals(user, repositories.users.findById(connection, user.id))
            assertEquals(user, repositories.users.findActiveByEmail(connection, user.emailNormalized))

            val storedCode = assertNotNull(repositories.loginCodes.findById(connection, code.id))
            assertContentEquals(code.codeHash, storedCode.codeHash)
            assertTrue(repositories.loginCodes.incrementFailedAttempts(connection, code.id))
            assertTrue(repositories.loginCodes.markConsumed(connection, code.id, now.plusSeconds(30)))

            val storedSession = assertNotNull(
                repositories.refreshSessions.findByTokenHash(connection, session.tokenHash)
            )
            assertEquals(session.id, storedSession.id)
            assertTrue(repositories.refreshSessions.revoke(connection, session.id, now.plusSeconds(40)))

            assertEquals(conversation, repositories.conversations.findById(connection, conversation.id))
            assertEquals(
                listOf(userMessage, assistantMessage),
                repositories.messages.listForConversation(connection, conversation.id)
            )
            assertEquals(listOf(memory), repositories.memories.listActiveForUser(connection, user.id))

            repositories.dailyUsage.add(connection, user.id, LocalDate.of(2026, 7, 22), 1, 12, 8, 40, now)
            val usage = repositories.dailyUsage.add(
                connection,
                user.id,
                LocalDate.of(2026, 7, 22),
                2,
                20,
                10,
                60,
                now.plusSeconds(60)
            )
            assertEquals(3, usage.requestCount)
            assertEquals(32, usage.inputTokens)
            assertEquals(18, usage.outputTokens)
            assertEquals(100, usage.estimatedCostMicros)
        }
    }

    @Test
    @Order(3)
    fun `transaction rolls back all writes after failure`() {
        val id = UUID.randomUUID()
        val now = Instant.parse("2026-07-22T06:00:00Z")

        assertFailsWith<IllegalStateException> {
            database.transaction { connection ->
                repositories.users.insert(
                    connection,
                    UserRecord(id, "rollback@example.com", "rollback@example.com", null, "UTC", now, now)
                )
                error("force rollback")
            }
        }

        database.transaction { connection ->
            assertNull(repositories.users.findById(connection, id))
        }
    }
}
