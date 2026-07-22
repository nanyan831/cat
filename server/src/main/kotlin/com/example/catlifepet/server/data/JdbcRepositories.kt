package com.example.catlifepet.server.data

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal class JdbcUserRepository : UserRepository {
    override fun insert(connection: Connection, user: UserRecord) {
        connection.prepareStatement(
            """
            INSERT INTO users (
                id, email_normalized, email_display, display_name, time_zone,
                created_at, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, user.id)
            statement.setString(2, user.emailNormalized)
            statement.setString(3, user.emailDisplay)
            statement.setString(4, user.displayName)
            statement.setString(5, user.timeZone)
            statement.setInstant(6, user.createdAt)
            statement.setInstant(7, user.updatedAt)
            statement.setNullableInstant(8, user.deletedAt)
            statement.executeUpdate()
        }
    }

    override fun findById(connection: Connection, id: UUID): UserRecord? {
        return connection.queryOne("SELECT * FROM users WHERE id = ?", id, ::mapUser)
    }

    override fun findActiveByEmail(connection: Connection, normalizedEmail: String): UserRecord? {
        return connection.queryOne(
            "SELECT * FROM users WHERE email_normalized = ? AND deleted_at IS NULL",
            normalizedEmail,
            ::mapUser
        )
    }

    override fun updateProfile(
        connection: Connection,
        id: UUID,
        displayName: String?,
        timeZone: String,
        updatedAt: Instant
    ): UserRecord? {
        return connection.prepareStatement(
            """
            UPDATE users
            SET display_name = ?, time_zone = ?, updated_at = ?
            WHERE id = ? AND deleted_at IS NULL
            RETURNING *
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, displayName)
            statement.setString(2, timeZone)
            statement.setInstant(3, updatedAt)
            statement.setObject(4, id)
            statement.executeQuery().use { result -> if (result.next()) mapUser(result) else null }
        }
    }

    override fun delete(connection: Connection, id: UUID): Boolean {
        return connection.prepareStatement("DELETE FROM users WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeUpdate() == 1
        }
    }
}

internal class JdbcLoginCodeRepository : LoginCodeRepository {
    override fun insert(connection: Connection, code: LoginCodeRecord) {
        connection.prepareStatement(
            """
            INSERT INTO login_codes (
                id, email_normalized, code_hash, request_ip_hash, expires_at,
                consumed_at, failed_attempts, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, code.id)
            statement.setString(2, code.emailNormalized)
            statement.setBytes(3, code.codeHash)
            statement.setBytes(4, code.requestIpHash)
            statement.setInstant(5, code.expiresAt)
            statement.setNullableInstant(6, code.consumedAt)
            statement.setInt(7, code.failedAttempts)
            statement.setInstant(8, code.createdAt)
            statement.executeUpdate()
        }
    }

    override fun findById(connection: Connection, id: UUID): LoginCodeRecord? {
        return connection.queryOne("SELECT * FROM login_codes WHERE id = ?", id, ::mapLoginCode)
    }

    override fun findLatestActiveForUpdate(
        connection: Connection,
        normalizedEmail: String,
        now: Instant
    ): LoginCodeRecord? {
        return connection.prepareStatement(
            """
            SELECT * FROM login_codes
            WHERE email_normalized = ? AND consumed_at IS NULL AND expires_at > ?
            ORDER BY created_at DESC
            LIMIT 1
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, normalizedEmail)
            statement.setInstant(2, now)
            statement.executeQuery().use { result -> if (result.next()) mapLoginCode(result) else null }
        }
    }

    override fun countCreatedSinceByEmail(
        connection: Connection,
        normalizedEmail: String,
        since: Instant
    ): Int {
        return connection.count(
            "SELECT COUNT(*) FROM login_codes WHERE email_normalized = ? AND created_at >= ?",
            normalizedEmail,
            since
        )
    }

    override fun countCreatedSinceByIpHash(
        connection: Connection,
        requestIpHash: ByteArray,
        since: Instant
    ): Int {
        return connection.prepareStatement(
            "SELECT COUNT(*) FROM login_codes WHERE request_ip_hash = ? AND created_at >= ?"
        ).use { statement ->
            statement.setBytes(1, requestIpHash)
            statement.setInstant(2, since)
            statement.executeQuery().use { result -> check(result.next()); result.getInt(1) }
        }
    }

    override fun invalidateActiveForEmail(
        connection: Connection,
        normalizedEmail: String,
        consumedAt: Instant
    ): Int {
        return connection.prepareStatement(
            "UPDATE login_codes SET consumed_at = ? WHERE email_normalized = ? AND consumed_at IS NULL"
        ).use { statement ->
            statement.setInstant(1, consumedAt)
            statement.setString(2, normalizedEmail)
            statement.executeUpdate()
        }
    }

    override fun lockRequestKeys(
        connection: Connection,
        normalizedEmail: String,
        requestIpHash: ByteArray
    ) {
        connection.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtext(?)), pg_advisory_xact_lock(hashtext(?))"
        ).use { statement ->
            statement.setString(1, "email:$normalizedEmail")
            statement.setString(2, "ip:${requestIpHash.toHexString()}")
            statement.executeQuery().use { result -> check(result.next()) }
        }
    }

    override fun markConsumed(connection: Connection, id: UUID, consumedAt: Instant): Boolean {
        return connection.prepareStatement(
            "UPDATE login_codes SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL"
        ).use { statement ->
            statement.setInstant(1, consumedAt)
            statement.setObject(2, id)
            statement.executeUpdate() == 1
        }
    }

    override fun incrementFailedAttempts(connection: Connection, id: UUID): Boolean {
        return connection.prepareStatement(
            "UPDATE login_codes SET failed_attempts = failed_attempts + 1 WHERE id = ? AND consumed_at IS NULL"
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeUpdate() == 1
        }
    }
}

internal class JdbcRefreshSessionRepository : RefreshSessionRepository {
    override fun insert(connection: Connection, session: RefreshSessionRecord) {
        connection.prepareStatement(
            """
            INSERT INTO refresh_sessions (
                id, user_id, family_id, token_hash, device_label, expires_at,
                revoked_at, replaced_by, created_at, last_used_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, session.id)
            statement.setObject(2, session.userId)
            statement.setObject(3, session.familyId)
            statement.setBytes(4, session.tokenHash)
            statement.setString(5, session.deviceLabel)
            statement.setInstant(6, session.expiresAt)
            statement.setNullableInstant(7, session.revokedAt)
            statement.setObject(8, session.replacedBy)
            statement.setInstant(9, session.createdAt)
            statement.setInstant(10, session.lastUsedAt)
            statement.executeUpdate()
        }
    }

    override fun findByTokenHash(connection: Connection, tokenHash: ByteArray): RefreshSessionRecord? {
        return connection.prepareStatement("SELECT * FROM refresh_sessions WHERE token_hash = ?").use { statement ->
            statement.setBytes(1, tokenHash)
            statement.executeQuery().use { result -> if (result.next()) mapRefreshSession(result) else null }
        }
    }

    override fun findByTokenHashForUpdate(
        connection: Connection,
        tokenHash: ByteArray
    ): RefreshSessionRecord? {
        return connection.prepareStatement(
            "SELECT * FROM refresh_sessions WHERE token_hash = ? FOR UPDATE"
        ).use { statement ->
            statement.setBytes(1, tokenHash)
            statement.executeQuery().use { result -> if (result.next()) mapRefreshSession(result) else null }
        }
    }

    override fun findById(connection: Connection, id: UUID): RefreshSessionRecord? {
        return connection.queryOne("SELECT * FROM refresh_sessions WHERE id = ?", id, ::mapRefreshSession)
    }

    override fun revoke(connection: Connection, id: UUID, revokedAt: Instant, replacedBy: UUID?): Boolean {
        return connection.prepareStatement(
            "UPDATE refresh_sessions SET revoked_at = ?, replaced_by = ?, last_used_at = ? WHERE id = ? AND revoked_at IS NULL"
        ).use { statement ->
            statement.setInstant(1, revokedAt)
            statement.setObject(2, replacedBy)
            statement.setInstant(3, revokedAt)
            statement.setObject(4, id)
            statement.executeUpdate() == 1
        }
    }

    override fun revokeFamily(connection: Connection, familyId: UUID, revokedAt: Instant): Int {
        return connection.prepareStatement(
            "UPDATE refresh_sessions SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL"
        ).use { statement ->
            statement.setInstant(1, revokedAt)
            statement.setObject(2, familyId)
            statement.executeUpdate()
        }
    }

    override fun revokeAllForUser(connection: Connection, userId: UUID, revokedAt: Instant): Int {
        return connection.prepareStatement(
            "UPDATE refresh_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL"
        ).use { statement ->
            statement.setInstant(1, revokedAt)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}

internal class JdbcConversationRepository : ConversationRepository {
    override fun insert(connection: Connection, conversation: ConversationRecord) {
        connection.prepareStatement(
            """
            INSERT INTO conversations (
                id, user_id, title, summary, created_at, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversation.id)
            statement.setObject(2, conversation.userId)
            statement.setString(3, conversation.title)
            statement.setString(4, conversation.summary)
            statement.setInstant(5, conversation.createdAt)
            statement.setInstant(6, conversation.updatedAt)
            statement.setNullableInstant(7, conversation.deletedAt)
            statement.executeUpdate()
        }
    }

    override fun findById(connection: Connection, id: UUID): ConversationRecord? {
        return connection.queryOne("SELECT * FROM conversations WHERE id = ?", id, ::mapConversation)
    }
}

internal class JdbcMessageRepository : MessageRepository {
    override fun insert(connection: Connection, message: MessageRecord) {
        connection.prepareStatement(
            """
            INSERT INTO messages (
                id, conversation_id, role, content, status, client_message_id,
                model, input_tokens, output_tokens, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, message.id)
            statement.setObject(2, message.conversationId)
            statement.setString(3, message.role.wireName)
            statement.setString(4, message.content)
            statement.setString(5, message.status.wireName)
            statement.setObject(6, message.clientMessageId)
            statement.setString(7, message.model)
            statement.setInt(8, message.inputTokens)
            statement.setInt(9, message.outputTokens)
            statement.setInstant(10, message.createdAt)
            statement.setInstant(11, message.updatedAt)
            statement.executeUpdate()
        }
    }

    override fun listForConversation(connection: Connection, conversationId: UUID): List<MessageRecord> {
        return connection.prepareStatement(
            "SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at, id"
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(mapMessage(result)) } }
        }
    }
}

internal class JdbcMemoryRepository : MemoryRepository {
    override fun insert(connection: Connection, memory: MemoryRecord) {
        connection.prepareStatement(
            """
            INSERT INTO memories (
                id, user_id, kind, content, source_conversation_id, confidence,
                created_at, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, memory.id)
            statement.setObject(2, memory.userId)
            statement.setString(3, memory.kind)
            statement.setString(4, memory.content)
            statement.setObject(5, memory.sourceConversationId)
            statement.setDouble(6, memory.confidence)
            statement.setInstant(7, memory.createdAt)
            statement.setInstant(8, memory.updatedAt)
            statement.setNullableInstant(9, memory.deletedAt)
            statement.executeUpdate()
        }
    }

    override fun listActiveForUser(connection: Connection, userId: UUID): List<MemoryRecord> {
        return connection.prepareStatement(
            "SELECT * FROM memories WHERE user_id = ? AND deleted_at IS NULL ORDER BY updated_at DESC, id"
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(mapMemory(result)) } }
        }
    }
}

internal class JdbcDailyUsageRepository : DailyUsageRepository {
    override fun add(
        connection: Connection,
        userId: UUID,
        date: LocalDate,
        requestCount: Int,
        inputTokens: Long,
        outputTokens: Long,
        estimatedCostMicros: Long,
        updatedAt: Instant
    ): DailyUsageRecord {
        require(requestCount >= 0 && inputTokens >= 0 && outputTokens >= 0 && estimatedCostMicros >= 0)
        return connection.prepareStatement(
            """
            INSERT INTO daily_usage (
                user_id, usage_date, request_count, input_tokens, output_tokens,
                estimated_cost_micros, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, usage_date) DO UPDATE SET
                request_count = daily_usage.request_count + EXCLUDED.request_count,
                input_tokens = daily_usage.input_tokens + EXCLUDED.input_tokens,
                output_tokens = daily_usage.output_tokens + EXCLUDED.output_tokens,
                estimated_cost_micros = daily_usage.estimated_cost_micros + EXCLUDED.estimated_cost_micros,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, date)
            statement.setInt(3, requestCount)
            statement.setLong(4, inputTokens)
            statement.setLong(5, outputTokens)
            statement.setLong(6, estimatedCostMicros)
            statement.setInstant(7, updatedAt)
            statement.executeQuery().use { result ->
                check(result.next()) { "Daily usage upsert returned no row" }
                mapDailyUsage(result)
            }
        }
    }

    override fun find(connection: Connection, userId: UUID, date: LocalDate): DailyUsageRecord? {
        return connection.prepareStatement(
            "SELECT * FROM daily_usage WHERE user_id = ? AND usage_date = ?"
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, date)
            statement.executeQuery().use { result -> if (result.next()) mapDailyUsage(result) else null }
        }
    }
}

private fun mapUser(result: ResultSet) = UserRecord(
    id = result.getObject("id", UUID::class.java),
    emailNormalized = result.getString("email_normalized"),
    emailDisplay = result.getString("email_display"),
    displayName = result.getString("display_name"),
    timeZone = result.getString("time_zone"),
    createdAt = result.getInstant("created_at"),
    updatedAt = result.getInstant("updated_at"),
    deletedAt = result.getNullableInstant("deleted_at")
)

private fun mapLoginCode(result: ResultSet) = LoginCodeRecord(
    id = result.getObject("id", UUID::class.java),
    emailNormalized = result.getString("email_normalized"),
    codeHash = result.getBytes("code_hash"),
    requestIpHash = result.getBytes("request_ip_hash"),
    expiresAt = result.getInstant("expires_at"),
    consumedAt = result.getNullableInstant("consumed_at"),
    failedAttempts = result.getInt("failed_attempts"),
    createdAt = result.getInstant("created_at")
)

private fun mapRefreshSession(result: ResultSet) = RefreshSessionRecord(
    id = result.getObject("id", UUID::class.java),
    userId = result.getObject("user_id", UUID::class.java),
    familyId = result.getObject("family_id", UUID::class.java),
    tokenHash = result.getBytes("token_hash"),
    deviceLabel = result.getString("device_label"),
    expiresAt = result.getInstant("expires_at"),
    revokedAt = result.getNullableInstant("revoked_at"),
    replacedBy = result.getObject("replaced_by", UUID::class.java),
    createdAt = result.getInstant("created_at"),
    lastUsedAt = result.getInstant("last_used_at")
)

private fun mapConversation(result: ResultSet) = ConversationRecord(
    id = result.getObject("id", UUID::class.java),
    userId = result.getObject("user_id", UUID::class.java),
    title = result.getString("title"),
    summary = result.getString("summary"),
    createdAt = result.getInstant("created_at"),
    updatedAt = result.getInstant("updated_at"),
    deletedAt = result.getNullableInstant("deleted_at")
)

private fun mapMessage(result: ResultSet) = MessageRecord(
    id = result.getObject("id", UUID::class.java),
    conversationId = result.getObject("conversation_id", UUID::class.java),
    role = MessageRole.entries.single { it.wireName == result.getString("role") },
    content = result.getString("content"),
    status = MessageStatus.entries.single { it.wireName == result.getString("status") },
    clientMessageId = result.getObject("client_message_id", UUID::class.java),
    model = result.getString("model"),
    inputTokens = result.getInt("input_tokens"),
    outputTokens = result.getInt("output_tokens"),
    createdAt = result.getInstant("created_at"),
    updatedAt = result.getInstant("updated_at")
)

private fun mapMemory(result: ResultSet) = MemoryRecord(
    id = result.getObject("id", UUID::class.java),
    userId = result.getObject("user_id", UUID::class.java),
    kind = result.getString("kind"),
    content = result.getString("content"),
    sourceConversationId = result.getObject("source_conversation_id", UUID::class.java),
    confidence = result.getDouble("confidence"),
    createdAt = result.getInstant("created_at"),
    updatedAt = result.getInstant("updated_at"),
    deletedAt = result.getNullableInstant("deleted_at")
)

private fun mapDailyUsage(result: ResultSet) = DailyUsageRecord(
    userId = result.getObject("user_id", UUID::class.java),
    usageDate = result.getObject("usage_date", LocalDate::class.java),
    requestCount = result.getInt("request_count"),
    inputTokens = result.getLong("input_tokens"),
    outputTokens = result.getLong("output_tokens"),
    estimatedCostMicros = result.getLong("estimated_cost_micros"),
    updatedAt = result.getInstant("updated_at")
)

private fun <T> Connection.queryOne(
    sql: String,
    argument: Any,
    mapper: (ResultSet) -> T
): T? {
    return prepareStatement(sql).use { statement ->
        statement.setObject(1, argument)
        statement.executeQuery().use { result -> if (result.next()) mapper(result) else null }
    }
}

private fun Connection.count(sql: String, text: String, instant: Instant): Int {
    return prepareStatement(sql).use { statement ->
        statement.setString(1, text)
        statement.setInstant(2, instant)
        statement.executeQuery().use { result -> check(result.next()); result.getInt(1) }
    }
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun java.sql.PreparedStatement.setInstant(index: Int, value: Instant) {
    setObject(index, value.atOffset(ZoneOffset.UTC))
}

private fun java.sql.PreparedStatement.setNullableInstant(index: Int, value: Instant?) {
    setObject(index, value?.atOffset(ZoneOffset.UTC))
}

private fun ResultSet.getInstant(column: String): Instant {
    return getObject(column, OffsetDateTime::class.java).toInstant()
}

private fun ResultSet.getNullableInstant(column: String): Instant? {
    return getObject(column, OffsetDateTime::class.java)?.toInstant()
}
