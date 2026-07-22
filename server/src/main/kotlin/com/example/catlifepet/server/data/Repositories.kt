package com.example.catlifepet.server.data

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface UserRepository {
    fun insert(connection: Connection, user: UserRecord)
    fun findById(connection: Connection, id: UUID): UserRecord?
    fun findActiveByEmail(connection: Connection, normalizedEmail: String): UserRecord?
    fun updateProfile(
        connection: Connection,
        id: UUID,
        displayName: String?,
        timeZone: String,
        updatedAt: Instant
    ): UserRecord?
    fun delete(connection: Connection, id: UUID): Boolean
}

interface LoginCodeRepository {
    fun insert(connection: Connection, code: LoginCodeRecord)
    fun findById(connection: Connection, id: UUID): LoginCodeRecord?
    fun findLatestActiveForUpdate(
        connection: Connection,
        normalizedEmail: String,
        now: Instant
    ): LoginCodeRecord?
    fun countCreatedSinceByEmail(connection: Connection, normalizedEmail: String, since: Instant): Int
    fun countCreatedSinceByIpHash(connection: Connection, requestIpHash: ByteArray, since: Instant): Int
    fun invalidateActiveForEmail(connection: Connection, normalizedEmail: String, consumedAt: Instant): Int
    fun lockRequestKeys(connection: Connection, normalizedEmail: String, requestIpHash: ByteArray)
    fun markConsumed(connection: Connection, id: UUID, consumedAt: Instant): Boolean
    fun incrementFailedAttempts(connection: Connection, id: UUID): Boolean
}

interface RefreshSessionRepository {
    fun insert(connection: Connection, session: RefreshSessionRecord)
    fun findByTokenHash(connection: Connection, tokenHash: ByteArray): RefreshSessionRecord?
    fun findByTokenHashForUpdate(connection: Connection, tokenHash: ByteArray): RefreshSessionRecord?
    fun findById(connection: Connection, id: UUID): RefreshSessionRecord?
    fun revoke(connection: Connection, id: UUID, revokedAt: Instant, replacedBy: UUID? = null): Boolean
    fun revokeFamily(connection: Connection, familyId: UUID, revokedAt: Instant): Int
    fun revokeAllForUser(connection: Connection, userId: UUID, revokedAt: Instant): Int
}

interface ConversationRepository {
    fun insert(connection: Connection, conversation: ConversationRecord)
    fun findById(connection: Connection, id: UUID): ConversationRecord?
    fun findActiveOwnedById(connection: Connection, id: UUID, userId: UUID, forUpdate: Boolean = false): ConversationRecord?
    fun listActiveForUser(connection: Connection, userId: UUID): List<ConversationRecord>
    fun touch(connection: Connection, id: UUID, userId: UUID, updatedAt: Instant): Boolean
    fun softDelete(connection: Connection, id: UUID, userId: UUID, deletedAt: Instant): Boolean
}

interface MessageRepository {
    fun insert(connection: Connection, message: MessageRecord)
    fun findById(connection: Connection, id: UUID): MessageRecord?
    fun listForConversation(connection: Connection, conversationId: UUID): List<MessageRecord>
    fun listCompletedForContext(connection: Connection, conversationId: UUID, limit: Int): List<MessageRecord>
    fun findByClientMessageId(connection: Connection, conversationId: UUID, clientMessageId: UUID): MessageRecord?
    fun findReplyTo(connection: Connection, userMessageId: UUID): MessageRecord?
    fun nextSequenceNumber(connection: Connection, conversationId: UUID): Long
    fun restart(connection: Connection, id: UUID, updatedAt: Instant): Boolean
    fun completeIfStreaming(
        connection: Connection,
        id: UUID,
        content: String,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        updatedAt: Instant
    ): Boolean
    fun finishIfStreaming(connection: Connection, id: UUID, status: MessageStatus, updatedAt: Instant): Boolean
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
