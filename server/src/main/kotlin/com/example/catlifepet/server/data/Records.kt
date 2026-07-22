package com.example.catlifepet.server.data

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class UserRecord(
    val id: UUID,
    val emailNormalized: String,
    val emailDisplay: String,
    val displayName: String?,
    val timeZone: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
)

data class LoginCodeRecord(
    val id: UUID,
    val emailNormalized: String,
    val codeHash: ByteArray,
    val requestIpHash: ByteArray,
    val expiresAt: Instant,
    val consumedAt: Instant?,
    val failedAttempts: Int,
    val createdAt: Instant
)

data class RefreshSessionRecord(
    val id: UUID,
    val userId: UUID,
    val familyId: UUID,
    val tokenHash: ByteArray,
    val deviceLabel: String?,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val replacedBy: UUID?,
    val createdAt: Instant,
    val lastUsedAt: Instant
)

data class ConversationRecord(
    val id: UUID,
    val userId: UUID,
    val title: String?,
    val summary: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
)

enum class MessageRole(val wireName: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool")
}

enum class MessageStatus(val wireName: String) {
    PENDING("pending"),
    STREAMING("streaming"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled")
}

data class MessageRecord(
    val id: UUID,
    val conversationId: UUID,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus,
    val clientMessageId: UUID?,
    val model: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class MemoryRecord(
    val id: UUID,
    val userId: UUID,
    val kind: String,
    val content: String,
    val sourceConversationId: UUID?,
    val confidence: Double,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
)

data class DailyUsageRecord(
    val userId: UUID,
    val usageDate: LocalDate,
    val requestCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostMicros: Long,
    val updatedAt: Instant
)
