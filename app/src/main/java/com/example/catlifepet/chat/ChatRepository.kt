package com.example.catlifepet.chat

import com.example.catlifepet.auth.AuthOutcome
import com.example.catlifepet.auth.AuthRepository
import com.example.catlifepet.chat.data.ChatDao
import com.example.catlifepet.chat.data.ConversationEntity
import com.example.catlifepet.chat.data.MessageEntity
import com.example.catlifepet.chat.data.PendingMessageEntity
import com.example.catlifepet.chat.network.ChatHttpClient
import com.example.catlifepet.chat.network.ChatHttpException
import com.example.catlifepet.chat.network.ConversationDto
import com.example.catlifepet.chat.network.MessageDto
import com.example.catlifepet.chat.network.TransportStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ChatRepository(
    private val authRepository: AuthRepository,
    private val http: ChatHttpClient,
    private val dao: ChatDao,
    private val now: () -> Long = System::currentTimeMillis
) : ChatDataSource {
    override fun observeConversations(): Flow<List<ChatConversation>> =
        dao.observeConversations().mapEntities { list -> list.map(ConversationEntity::toDomain) }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = combine(
        dao.observeMessages(conversationId),
        dao.observePending(conversationId)
    ) { messages, pending ->
        messages.map(MessageEntity::toDomain) + pending.map(PendingMessageEntity::toDomain)
    }

    override suspend fun loadWorkspace(preferredConversationId: String?): ChatLoadResult {
        val local = dao.listConversations()
        return try {
            ensureAuthenticated()
            val remote = authenticatedRequest { http.listConversations().conversations }
            dao.replaceConversations(remote.map(ConversationDto::toEntity))
            val selected = remote.firstOrNull { it.id == preferredConversationId }
                ?: remote.firstOrNull()
                ?: authenticatedRequest { http.createConversation(null) }.also {
                    dao.upsertConversation(it.toEntity())
                }
            syncMessages(selected.id)
            ChatLoadResult.Ready(selected.id)
        } catch (error: ChatHttpException) {
            when {
                error.status == 401 -> ChatLoadResult.SessionExpired
                local.isNotEmpty() -> ChatLoadResult.Ready(
                    local.firstOrNull { it.id == preferredConversationId }?.id ?: local.first().id,
                    offline = true
                )
                else -> ChatLoadResult.Failure("无法连接服务器，登录后再来和小猫聊天吧。")
            }
        }
    }

    override suspend fun createConversation(title: String?): ChatLoadResult = try {
        ensureAuthenticated()
        val conversation = authenticatedRequest { http.createConversation(title) }
        dao.upsertConversation(conversation.toEntity())
        ChatLoadResult.Ready(conversation.id)
    } catch (error: ChatHttpException) {
        error.toLoadFailure()
    }

    override suspend fun selectConversation(conversationId: String): ChatLoadResult = try {
        ensureAuthenticated()
        syncMessages(conversationId)
        ChatLoadResult.Ready(conversationId)
    } catch (error: ChatHttpException) {
        if (error.status == 401) ChatLoadResult.SessionExpired
        else ChatLoadResult.Ready(conversationId, offline = true)
    }

    override fun sendMessage(
        conversationId: String,
        content: String,
        clientMessageId: String
    ): Flow<ChatSendEvent> = flow {
        val normalized = content.trim()
        val pending = PendingMessageEntity(
            clientMessageId,
            conversationId,
            normalized,
            now(),
            state = "sending"
        )
        dao.upsertPending(pending)
        try {
            ensureAuthenticated()
            var retryAuthentication = collectStream(conversationId, normalized, clientMessageId) { emit(it) }
            if (retryAuthentication) {
                when (authRepository.ensureAuthenticated(forceRefresh = true)) {
                    is AuthOutcome.Success -> retryAuthentication = collectStream(
                        conversationId,
                        normalized,
                        clientMessageId
                    ) { emit(it) }
                    is AuthOutcome.Failure -> Unit
                }
            }
            if (retryAuthentication) {
                val message = "登录已失效，请重新登录。"
                dao.upsertPending(pending.copy(state = "failed", errorMessage = message))
                emit(ChatSendEvent.Failure("invalid_access_token", message, false, sessionExpired = true))
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                dao.upsertPending(pending.copy(state = "stopped", errorMessage = "已停止生成，可以重试。"))
            }
            throw error
        } catch (error: ChatHttpException) {
            val message = if (error.status == 401) "登录已失效，请重新登录。" else "网络中断，消息已保留。"
            dao.upsertPending(pending.copy(state = "failed", errorMessage = message))
            emit(ChatSendEvent.Failure(error.code, message, error.retryable, error.status == 401))
        }
    }

    override suspend fun deleteConversation(conversationId: String): ChatLoadResult = try {
        ensureAuthenticated()
        authenticatedRequest { http.deleteConversation(conversationId) }
        dao.deleteConversation(conversationId)
        val remaining = dao.listConversations()
        if (remaining.isNotEmpty()) ChatLoadResult.Ready(remaining.first().id)
        else createConversation(null)
    } catch (error: ChatHttpException) {
        error.toLoadFailure()
    }

    private suspend fun collectStream(
        conversationId: String,
        content: String,
        clientMessageId: String,
        emitEvent: suspend (ChatSendEvent) -> Unit
    ): Boolean {
        var unauthorized = false
        http.streamMessage(conversationId, content, clientMessageId).collect { event ->
            when (event) {
                is TransportStreamEvent.Delta -> emitEvent(ChatSendEvent.Delta(event.messageId, event.text))
                is TransportStreamEvent.Completed -> {
                    dao.upsertMessage(event.message.toEntity(conversationId))
                    val synced = runCatching { syncMessages(conversationId) }.isSuccess
                    if (!synced) {
                        dao.listPending(conversationId).firstOrNull {
                            it.clientMessageId == clientMessageId
                        }?.let { dao.upsertPending(it.copy(state = "sent", errorMessage = null)) }
                    }
                    emitEvent(ChatSendEvent.Completed(event.message.toDomain(conversationId)))
                }
                is TransportStreamEvent.Failure -> {
                    if (event.status == 401) {
                        unauthorized = true
                    } else {
                        val userMessage = when (event.code) {
                            "daily_quota_exceeded" -> "今天的聊天次数已经用完了，我们明天再继续吧。"
                            "rate_limited" -> "说得有点快啦，稍等一会儿再试。"
                            else -> event.message
                        }
                        val pending = dao.listPending(conversationId).firstOrNull {
                            it.clientMessageId == clientMessageId
                        }
                        if (pending != null) {
                            dao.upsertPending(pending.copy(state = "failed", errorMessage = userMessage))
                        }
                        emitEvent(ChatSendEvent.Failure(event.code, userMessage, event.retryable))
                    }
                }
            }
        }
        return unauthorized
    }

    private suspend fun syncMessages(conversationId: String) {
        val messages = authenticatedRequest { http.listMessages(conversationId).messages }
        dao.replaceServerHistory(conversationId, messages.map { it.toEntity(conversationId) })
        val confirmedIds = messages.mapNotNullTo(hashSetOf()) { it.clientMessageId }
        dao.listPending(conversationId)
            .filter { it.clientMessageId in confirmedIds }
            .forEach { dao.deletePending(it.clientMessageId) }
    }

    private suspend fun ensureAuthenticated() {
        when (val outcome = authRepository.ensureAuthenticated()) {
            is AuthOutcome.Success -> Unit
            is AuthOutcome.Failure -> throw ChatHttpException(
                outcome.httpStatus,
                outcome.code,
                outcome.message,
                outcome.retryable
            )
        }
    }

    private suspend fun <T> authenticatedRequest(block: suspend () -> T): T {
        return try {
            block()
        } catch (first: ChatHttpException) {
            if (first.status != 401) throw first
            when (val refreshed = authRepository.ensureAuthenticated(forceRefresh = true)) {
                is AuthOutcome.Success -> block()
                is AuthOutcome.Failure -> throw ChatHttpException(
                    refreshed.httpStatus ?: 401,
                    refreshed.code,
                    refreshed.message,
                    refreshed.retryable
                )
            }
        }
    }

    private fun ChatHttpException.toLoadFailure(): ChatLoadResult =
        if (status == 401) ChatLoadResult.SessionExpired else ChatLoadResult.Failure("操作没有完成，请稍后重试。")
}

private fun ConversationDto.toEntity() = ConversationEntity(id, title, createdAt, updatedAt)
private fun ConversationEntity.toDomain() = ChatConversation(id, title, createdAt, updatedAt)

private fun MessageDto.toEntity(conversationId: String) = MessageEntity(
    id, conversationId, sequenceNumber, role, content, status,
    clientMessageId, replyToMessageId, model, createdAt, updatedAt
)

private fun MessageDto.toDomain(conversationId: String) = toEntity(conversationId).toDomain()

private fun MessageEntity.toDomain() = ChatMessage(
    id, conversationId, sequenceNumber, role, content, status, clientMessageId
)

private fun PendingMessageEntity.toDomain() = ChatMessage(
    id = clientMessageId,
    conversationId = conversationId,
    sequenceNumber = Long.MAX_VALUE - createdAtEpochMillis,
    role = "user",
    content = content,
    status = state,
    clientMessageId = clientMessageId,
    pending = true,
    errorMessage = errorMessage
)

private fun <T, R> Flow<T>.mapEntities(transform: suspend (T) -> R): Flow<R> =
    map(transform)
