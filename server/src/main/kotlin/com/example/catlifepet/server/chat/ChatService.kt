package com.example.catlifepet.server.chat

import com.example.catlifepet.server.ai.AiException
import com.example.catlifepet.server.ai.AiGateway
import com.example.catlifepet.server.ai.AiRequest
import com.example.catlifepet.server.ai.AiStreamEvent
import com.example.catlifepet.server.data.ConversationRecord
import com.example.catlifepet.server.config.AiSettings
import com.example.catlifepet.server.data.AiRequestAuditRecord
import com.example.catlifepet.server.data.DatabaseFactory
import com.example.catlifepet.server.data.MessageRecord
import com.example.catlifepet.server.data.MessageRole
import com.example.catlifepet.server.data.MessageStatus
import com.example.catlifepet.server.data.Repositories
import com.example.catlifepet.server.http.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

internal class ChatService(
    private val database: DatabaseFactory,
    private val repositories: Repositories,
    private val aiGateway: AiGateway,
    private val settings: AiSettings = AiSettings(),
    private val clock: Clock = Clock.systemUTC(),
    private val requestLimiter: ChatRequestLimiter = ChatRequestLimiter(settings, clock),
    private val nanoTime: () -> Long = System::nanoTime
) {
    suspend fun createConversation(userId: UUID, request: CreateConversationRequest): ConversationResponse {
        val title = request.title?.trim()?.takeIf(String::isNotEmpty)
        if (title != null && title.length > MAX_TITLE_LENGTH) invalid("Conversation title is too long.")
        val now = clock.instant()
        val conversation = ConversationRecord(UUID.randomUUID(), userId, title, null, now, now)
        io { database.transaction { repositories.conversations.insert(it, conversation) } }
        return conversation.toResponse()
    }

    suspend fun listConversations(userId: UUID): ConversationListResponse = io {
        ConversationListResponse(database.transaction {
            repositories.conversations.listActiveForUser(it, userId).map(ConversationRecord::toResponse)
        })
    }

    suspend fun listMessages(userId: UUID, conversationId: UUID): MessageListResponse = io {
        database.transaction { connection ->
            requireConversation(connection, conversationId, userId)
            MessageListResponse(repositories.messages.listForConversation(connection, conversationId).map(MessageRecord::toResponse))
        }
    }

    suspend fun deleteConversation(userId: UUID, conversationId: UUID) = io {
        val deleted = database.transaction {
            repositories.conversations.softDelete(it, conversationId, userId, clock.instant())
        }
        if (!deleted) notFound()
    }

    suspend fun deleteAllConversations(userId: UUID) = io {
        database.transaction { repositories.conversations.softDeleteAll(it, userId, clock.instant()) }
    }

    suspend fun prepareTurn(
        userId: UUID,
        conversationId: UUID,
        request: SendMessageRequest,
        remoteIp: String = "unknown"
    ): PreparedTurn = io {
        val content = request.content.trim()
        if (content.isEmpty()) invalid("Message content must not be blank.")
        if (content.length > MAX_MESSAGE_LENGTH) invalid("Message content is too long.")
        val clientMessageId = request.clientMessageId.toUuidOrNull()
            ?: invalid("clientMessageId must be a UUID.")
        val now = clock.instant()
        requestLimiter.check(userId, remoteIp)

        database.transaction { connection ->
            val conversation = requireConversation(connection, conversationId, userId, forUpdate = true)
            val existingUser = repositories.messages.findByClientMessageId(
                connection,
                conversationId,
                clientMessageId
            )
            val userMessage: MessageRecord
            val assistantMessage: MessageRecord
            var replay = false

            if (existingUser != null) {
                if (existingUser.content != content) conflict("client_message_conflict", "clientMessageId was already used.")
                userMessage = existingUser
                assistantMessage = repositories.messages.findReplyTo(connection, userMessage.id)
                    ?: error("Stored user message has no assistant reply row")
                when (assistantMessage.status) {
                    MessageStatus.COMPLETED -> replay = true
                    MessageStatus.FAILED, MessageStatus.CANCELLED -> {
                        if (!repositories.messages.restart(connection, assistantMessage.id, now)) {
                            conflict("message_retry_conflict", "The message could not be retried.")
                        }
                    }
                    else -> conflict("turn_in_progress", "This message is already being processed.")
                }
            } else {
                val next = repositories.messages.nextSequenceNumber(connection, conversationId)
                userMessage = MessageRecord(
                    UUID.randomUUID(), conversationId, next, MessageRole.USER, content,
                    MessageStatus.COMPLETED, clientMessageId, null, null, 0, 0, now, now
                )
                assistantMessage = MessageRecord(
                    UUID.randomUUID(), conversationId, next + 1, MessageRole.ASSISTANT, "",
                    MessageStatus.STREAMING, null, userMessage.id, null, 0, 0, now, now
                )
                repositories.messages.insert(connection, userMessage)
                repositories.messages.insert(connection, assistantMessage)
                check(repositories.conversations.touch(connection, conversationId, userId, now))
            }

            val context = repositories.messages.listCompletedForContext(
                connection,
                conversation.id,
                PromptContextBuilder.MAX_RECENT_MESSAGES
            )
            val memories = repositories.memories.listActiveForUser(connection, userId)
            val promptContext = PromptContextBuilder.build(conversation.summary, memories, context)
            if (!replay) {
                val usage = repositories.dailyUsage.reserveRequest(
                    connection,
                    userId,
                    LocalDate.ofInstant(now, ZoneOffset.UTC),
                    settings.dailyRequestLimit,
                    now
                )
                if (usage == null) {
                    throw ApiException(
                        HttpStatusCode.TooManyRequests,
                        "daily_quota_exceeded",
                        "The daily chat limit has been reached."
                    )
                }
            }
            PreparedTurn(
                userId,
                conversation,
                userMessage,
                assistantMessage,
                promptContext,
                CompanionSafetyPolicy.evaluate(content),
                replay
            )
        }
    }

    fun streamTurn(turn: PreparedTurn): Flow<ChatStreamEvent> = flow {
        if (turn.replay) {
            emit(ChatStreamEvent.Delta(turn.assistantMessage.id.toString(), turn.assistantMessage.content))
            emit(ChatStreamEvent.Completed(turn.assistantMessage.toResponse()))
            return@flow
        }

        val startedAt = nanoTime()
        if (turn.safetyDecision is SafetyDecision.FixedReply) {
            val decision = turn.safetyDecision
            emit(ChatStreamEvent.Delta(turn.assistantMessage.id.toString(), decision.text))
            complete(turn, decision.text, LOCAL_SAFETY_MODEL, 0, 0, decision.outcome, null, elapsedMillis(startedAt))
            emit(ChatStreamEvent.Completed(loadMessage(turn.assistantMessage.id).toResponse()))
            return@flow
        }

        val request = AiRequest(
            instructions = turn.promptContext.instructions,
            messages = turn.promptContext.messages,
            safetyIdentifier = safetyIdentifier(turn.userId)
        )
        var completed = false
        try {
            aiGateway.stream(request).collect { event ->
                when (event) {
                    is AiStreamEvent.Delta -> if (!completed && event.text.isNotEmpty()) {
                        emit(ChatStreamEvent.Delta(turn.assistantMessage.id.toString(), event.text))
                    }
                    is AiStreamEvent.Completed -> if (!completed) {
                        val result = event.result
                        val saved = complete(
                            turn,
                            result.text,
                            result.model,
                            result.usage?.inputTokens ?: 0,
                            result.usage?.outputTokens ?: 0,
                            "completed",
                            null,
                            elapsedMillis(startedAt)
                        )
                        if (!saved) conflict("message_finalization_conflict", "The reply was already finalized.")
                        completed = true
                        emit(ChatStreamEvent.Completed(loadMessage(turn.assistantMessage.id).toResponse()))
                    }
                }
            }
            if (!completed) throw IllegalStateException("AI stream ended without a completed event")
        } catch (error: CancellationException) {
            withContext(NonCancellable) { cancel(turn, elapsedMillis(startedAt)) }
            throw error
        } catch (error: IOException) {
            withContext(NonCancellable) { cancel(turn, elapsedMillis(startedAt)) }
            throw error
        } catch (error: AiException) {
            if (error.retryable) {
                emit(ChatStreamEvent.Delta(turn.assistantMessage.id.toString(), FALLBACK_TEXT))
                complete(
                    turn, FALLBACK_TEXT, LOCAL_FALLBACK_MODEL, 0, 0,
                    "fallback", error.code, elapsedMillis(startedAt)
                )
                emit(ChatStreamEvent.Completed(loadMessage(turn.assistantMessage.id).toResponse()))
            } else {
                fail(turn, error.code, elapsedMillis(startedAt))
                emit(ChatStreamEvent.Error(error.code, error.message ?: "AI request failed.", false))
            }
        } catch (error: ApiException) {
            fail(turn, error.code, elapsedMillis(startedAt))
            emit(ChatStreamEvent.Error(error.code, error.message, false))
        } catch (error: Throwable) {
            fail(turn, "ai_stream_failed", elapsedMillis(startedAt))
            emit(ChatStreamEvent.Error("ai_stream_failed", "The reply was interrupted.", true))
        }
    }

    private suspend fun complete(
        turn: PreparedTurn,
        content: String,
        model: String,
        input: Int,
        output: Int,
        outcome: String,
        errorCategory: String?,
        latencyMillis: Long
    ): Boolean = io {
        database.transaction { connection ->
            val saved = repositories.messages.completeIfStreaming(
                connection, turn.assistantMessage.id, content, model, input, output, clock.instant()
            )
            if (saved) {
                val summary = ConversationSummaryBuilder.build(
                    repositories.messages.listForConversation(connection, turn.conversation.id)
                )
                repositories.conversations.updateSummary(
                    connection,
                    turn.conversation.id,
                    turn.userId,
                    summary,
                    clock.instant()
                )
                repositories.dailyUsage.add(
                    connection,
                    turn.userId,
                    LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC),
                    0,
                    input.toLong(),
                    output.toLong(),
                    0,
                    clock.instant()
                )
                repositories.aiRequestAudit.insert(
                    connection,
                    audit(turn, model, outcome, errorCategory, input, output, latencyMillis)
                )
            }
            saved
        }
    }

    private suspend fun fail(turn: PreparedTurn, code: String, latencyMillis: Long) =
        finish(turn, MessageStatus.FAILED, code, latencyMillis)
    private suspend fun cancel(turn: PreparedTurn, latencyMillis: Long) =
        finish(turn, MessageStatus.CANCELLED, "client_cancelled", latencyMillis)

    private suspend fun finish(
        turn: PreparedTurn,
        status: MessageStatus,
        errorCategory: String,
        latencyMillis: Long
    ) = io {
        database.transaction { connection ->
            if (repositories.messages.finishIfStreaming(
                    connection,
                    turn.assistantMessage.id,
                    status,
                    clock.instant()
                )
            ) {
                repositories.aiRequestAudit.insert(
                    connection,
                    audit(turn, null, status.wireName, errorCategory, 0, 0, latencyMillis)
                )
            }
        }
    }

    private fun audit(
        turn: PreparedTurn,
        model: String?,
        outcome: String,
        errorCategory: String?,
        input: Int,
        output: Int,
        latencyMillis: Long
    ) = AiRequestAuditRecord(
        UUID.randomUUID(), turn.userId, turn.conversation.id, turn.assistantMessage.id,
        model, outcome, errorCategory, input, output, latencyMillis, clock.instant()
    )

    private fun elapsedMillis(startedAt: Long) = ((nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0)

    private suspend fun loadMessage(id: UUID): MessageRecord = io {
        database.transaction { connection ->
            repositories.messages.findById(connection, id) ?: error("Finalized message disappeared")
        }
    }

    private fun requireConversation(
        connection: java.sql.Connection,
        id: UUID,
        userId: UUID,
        forUpdate: Boolean = false
    ): ConversationRecord = repositories.conversations.findActiveOwnedById(connection, id, userId, forUpdate)
        ?: notFound()

    private fun safetyIdentifier(userId: UUID): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toString().toByteArray(Charsets.UTF_8))
        return "user_" + digest.joinToString("") { "%02x".format(it) }.take(48)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
    private fun invalid(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_request", message)
    private fun conflict(code: String, message: String): Nothing = throw ApiException(HttpStatusCode.Conflict, code, message)
    private fun notFound(): Nothing = throw ApiException(HttpStatusCode.NotFound, "not_found", "The conversation was not found.")

    internal data class PreparedTurn(
        val userId: UUID,
        val conversation: ConversationRecord,
        val userMessage: MessageRecord,
        val assistantMessage: MessageRecord,
        val promptContext: PromptContext,
        val safetyDecision: SafetyDecision,
        val replay: Boolean
    )

    private companion object {
        const val MAX_TITLE_LENGTH = 160
        const val MAX_MESSAGE_LENGTH = 4_000
        const val LOCAL_SAFETY_MODEL = "catlifepet-safety"
        const val LOCAL_FALLBACK_MODEL = "catlifepet-fallback"
        const val FALLBACK_TEXT = "我还在这里，只是现在回复有点慢。我们稍后再试一次，好吗？"
    }
}
