package com.example.catlifepet.server.data

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface UserRepository {
    fun insert(connection: Connection, user: UserRecord)
    fun findById(connection: Connection, id: UUID): UserRecord?
    fun findActiveByEmail(connection: Connection, normalizedEmail: String): UserRecord?
}

interface LoginCodeRepository {
    fun insert(connection: Connection, code: LoginCodeRecord)
    fun findById(connection: Connection, id: UUID): LoginCodeRecord?
    fun markConsumed(connection: Connection, id: UUID, consumedAt: Instant): Boolean
    fun incrementFailedAttempts(connection: Connection, id: UUID): Boolean
}

interface RefreshSessionRepository {
    fun insert(connection: Connection, session: RefreshSessionRecord)
    fun findByTokenHash(connection: Connection, tokenHash: ByteArray): RefreshSessionRecord?
    fun revoke(connection: Connection, id: UUID, revokedAt: Instant, replacedBy: UUID? = null): Boolean
}

interface ConversationRepository {
    fun insert(connection: Connection, conversation: ConversationRecord)
    fun findById(connection: Connection, id: UUID): ConversationRecord?
}

interface MessageRepository {
    fun insert(connection: Connection, message: MessageRecord)
    fun listForConversation(connection: Connection, conversationId: UUID): List<MessageRecord>
}

interface MemoryRepository {
    fun insert(connection: Connection, memory: MemoryRecord)
    fun listActiveForUser(connection: Connection, userId: UUID): List<MemoryRecord>
}

interface DailyUsageRepository {
    fun add(
        connection: Connection,
        userId: UUID,
        date: LocalDate,
        requestCount: Int,
        inputTokens: Long,
        outputTokens: Long,
        estimatedCostMicros: Long,
        updatedAt: Instant
    ): DailyUsageRecord

    fun find(connection: Connection, userId: UUID, date: LocalDate): DailyUsageRecord?
}

data class Repositories(
    val users: UserRepository = JdbcUserRepository(),
    val loginCodes: LoginCodeRepository = JdbcLoginCodeRepository(),
    val refreshSessions: RefreshSessionRepository = JdbcRefreshSessionRepository(),
    val conversations: ConversationRepository = JdbcConversationRepository(),
    val messages: MessageRepository = JdbcMessageRepository(),
    val memories: MemoryRepository = JdbcMemoryRepository(),
    val dailyUsage: DailyUsageRepository = JdbcDailyUsageRepository()
)
