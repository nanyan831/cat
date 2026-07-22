package com.example.catlifepet.server.memory

import com.example.catlifepet.server.data.DatabaseFactory
import com.example.catlifepet.server.data.MemoryRecord
import com.example.catlifepet.server.data.Repositories
import com.example.catlifepet.server.http.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

internal class MemoryService(
    private val database: DatabaseFactory,
    private val repositories: Repositories,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun list(userId: UUID): MemoryListResponse = io {
        MemoryListResponse(database.transaction { connection ->
            repositories.memories.listActiveForUser(connection, userId).map(MemoryRecord::toResponse)
        })
    }

    suspend fun create(userId: UUID, request: CreateMemoryRequest): MemoryResponse = io {
        val kind = MemoryKind.fromWireName(request.kind.trim().lowercase())
            ?: invalid("Memory kind must be nickname, preferred_address, routine, or preference.")
        val content = request.content.trim()
        if (content.isEmpty()) invalid("Memory content must not be blank.")
        if (content.length > MAX_CONTENT_LENGTH) invalid("Memory content is too long.")
        val sourceConversationId = request.sourceConversationId?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: invalid("sourceConversationId must be a UUID.")
        }
        val now = clock.instant()
        val memory = database.transaction { connection ->
            if (sourceConversationId != null && repositories.conversations.findActiveOwnedById(
                    connection,
                    sourceConversationId,
                    userId
                ) == null
            ) notFound()
            MemoryRecord(
                id = UUID.randomUUID(),
                userId = userId,
                kind = kind.wireName,
                content = content,
                sourceConversationId = sourceConversationId,
                confidence = 1.0,
                createdAt = now,
                updatedAt = now
            ).also { repositories.memories.insert(connection, it) }
        }
        memory.toResponse()
    }

    suspend fun delete(userId: UUID, memoryId: UUID) = io {
        val deleted = database.transaction {
            repositories.memories.softDelete(it, memoryId, userId, clock.instant())
        }
        if (!deleted) notFound()
    }

    suspend fun deleteAll(userId: UUID) = io {
        database.transaction { repositories.memories.softDeleteAll(it, userId, clock.instant()) }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun invalid(message: String): Nothing =
        throw ApiException(HttpStatusCode.BadRequest, "invalid_request", message)
    private fun notFound(): Nothing =
        throw ApiException(HttpStatusCode.NotFound, "not_found", "The memory was not found.")

    private companion object {
        const val MAX_CONTENT_LENGTH = 500
    }
}
