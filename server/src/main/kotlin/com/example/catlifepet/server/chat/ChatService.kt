package com.example.catlifepet.server.chat

import com.example.catlifepet.server.ai.AiException
import com.example.catlifepet.server.ai.AiGateway
import com.example.catlifepet.server.ai.AiMessage
import com.example.catlifepet.server.ai.AiRequest
import com.example.catlifepet.server.ai.AiRole
import com.example.catlifepet.server.ai.AiStreamEvent
import com.example.catlifepet.server.data.ConversationRecord
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
import java.util.UUID

internal class ChatService(
    private val database: DatabaseFactory,
    private val repositories: Repositories,
    private val aiGateway: AiGateway,
    private val clock: Clock = Clock.systemUTC()
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

    suspend fun prepareTurn(
        userId: UUID,
        conversationId: UUID,
        request: SendMessageRequest
    ): PreparedTurn = io {
        val content = request.content.trim()
        if (content.isEmpty()) invalid("Message content must not be blank.")
        if (content.length > MAX_MESSAGE_LENGTH) invalid("Message content is too long.")
        val clientMessageId = request.clientMessageId.toUuidOrNull()
            ?: invalid("clientMessageId must be a UUID.")
        val now = clock.instant()

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

            val context = repositories.messages.listCompletedForContext(connection, conversation.id, CONTEXT_MESSAGES)
            PreparedTurn(userId, conversation, userMessage, assistantMessage, context, replay)
        }
    }

    fun streamTurn(turn: PreparedTurn): Flow<ChatStreamEvent> = flow {
        if (turn.replay) {
            emit(ChatStreamEvent.Delta(turn.assistantMessage.id.toString(), turn.assistantMessage.content))
            emit(ChatStreamEvent.Completed(turn.assistantMessage.toResponse()))
            return@flow
        }

        val request = AiRequest(
            instructions = COMPANION_INSTRUCTIONS,
            messages = turn.context.mapNotNull { message ->
                val role = when (message.role) {
                    MessageRole.USER -> AiRole.USER
                    MessageRole.ASSISTANT -> AiRole.ASSISTANT
                    else -> return@mapNotNull null
                }
                AiMessage(role, message.content)
            },
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
                        val saved = complete(turn, result.text, result.model, result.usage?.inputTokens ?: 0, result.usage?.outputTokens ?: 0)
                        if (!saved) conflict("message_finalization_conflict", "The reply was already finalized.")
                        completed = true
                        emit(ChatStreamEvent.Completed(loadMessage(turn.assistantMessage.id).toResponse()))
                    }
                }
            }
            if (!completed) throw IllegalStateException("AI stream ended without a completed event")
        } catch (error: CancellationException) {
            withContext(NonCancellable) { cancel(turn) }
            throw error
        } catch (error: IOException) {
            withContext(NonCancellable) { cancel(turn) }
            throw error
        } catch (error: AiException) {
            fail(turn)
            emit(ChatStreamEvent.Error(error.code, error.message ?: "AI request failed.", error.retryable))
        } catch (error: ApiException) {
            fail(turn)
            emit(ChatStreamEvent.Error(error.code, error.message, false))
        } catch (error: Throwable) {
            fail(turn)
            emit(ChatStreamEvent.Error("ai_stream_failed", "The reply was interrupted.", true))
        }
    }

    private suspend fun complete(turn: PreparedTurn, content: String, model: String, input: Int, output: Int): Boolean = io {
        database.transaction { connection ->
            val saved = repositories.messages.completeIfStreaming(
                connection, turn.assistantMessage.id, content, model, input, output, clock.instant()
            )
            if (saved) repositories.conversations.touch(connection, turn.conversation.id, turn.userId, clock.instant())
            saved
        }
    }

    private suspend fun fail(turn: PreparedTurn) = finish(turn, MessageStatus.FAILED)
    private suspend fun cancel(turn: PreparedTurn) = finish(turn, MessageStatus.CANCELLED)

    private suspend fun finish(turn: PreparedTurn, status: MessageStatus) = io {
        database.transaction {
            repositories.messages.finishIfStreaming(it, turn.assistantMessage.id, status, clock.instant())
        }
    }

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
        val context: List<MessageRecord>,
        val replay: Boolean
    )

    private companion object {
        const val MAX_TITLE_LENGTH = 160
        const val MAX_MESSAGE_LENGTH = 4_000
        const val CONTEXT_MESSAGES = 20
        const val COMPANION_INSTRUCTIONS =
            "You are the user's gentle CatLifePet companion. Reply warmly and concisely in the user's language."
    }
}
