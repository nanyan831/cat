package com.example.catlifepet.chat

import android.util.Log
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
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
            var retryAuthentication = withTimeout(STREAM_TIMEOUT_MS) {
                collectStream(conversationId, normalized, clientMessageId) { emit(it) }
            }
            if (retryAuthentication) {
                when (authRepository.ensureAuthenticated(forceRefresh = true)) {
                    is AuthOutcome.Success -> retryAuthentication = withTimeout(STREAM_TIMEOUT_MS) {
                        collectStream(conversationId, normalized, clientMessageId) { emit(it) }
                    }
                    is AuthOutcome.Failure -> Unit
                }
            }
            if (retryAuthentication) {
                val message = "登录已失效，请重新登录。"
                dao.upsertPending(pending.copy(state = "failed", errorMessage = message))
                emit(ChatSendEvent.Failure("invalid_access_token", message, false, sessionExpired = true))
            }
        } catch (error: TimeoutCancellationException) {
            logWarning("Chat stream timed out: clientMessageId=$clientMessageId")
            val message = TransportStreamEvent.Failure(null, "ai_timeout", "", true).toUserMessage()
            dao.upsertPending(pending.copy(state = "failed", errorMessage = message))
            emit(ChatSendEvent.Failure("ai_timeout", message, true))
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                dao.upsertPending(pending.copy(state = "stopped", errorMessage = "已停止生成，可以重试。"))
            }
            throw error
        } catch (error: ChatHttpException) {
            logWarning("Chat send failed: status=${error.status}, code=${error.code}, message=${error.message}")
            val message = error.toUserMessage()
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
                    logWarning(
                        "Chat stream failure event: status=${event.status}, code=${event.code}, message=${event.message}"
                    )
                    if (event.status == 401) {
                        unauthorized = true
                    } else {
                        val userMessage = event.toUserMessage()
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

    private fun ChatHttpException.toUserMessage(): String =
        TransportStreamEvent.Failure(status, code, message.orEmpty(), retryable).toUserMessage()

    private fun TransportStreamEvent.Failure.toUserMessage(): String {
        val message = when (code) {
            "invalid_access_token", "missing_access_token", "token_expired" -> "登录已失效，请重新登录。"
            "daily_quota_exceeded" -> "今天的聊天次数已经用完了，我们明天再继续吧。"
            "rate_limited" -> "说得有点快啦，稍等一会儿再试。"
            "turn_in_progress" -> "上一条还在生成，稍等一下再试。"
            "client_message_conflict", "message_retry_conflict" -> "这条消息状态不一致，请重新发送一条新的。"
            "invalid_request" -> "这条消息格式不太对，换一种说法再试试。"
            "message_too_long" -> "这条消息有点长，分成几段发给小猫吧。"
            "conversation_not_found", "not_found" -> "这段聊天暂时找不到了，重新新建一段聊天吧。"
            "message_finalization_conflict" -> "这条回复已经结束了，请重新发送一条新的。"
            "ai_provider_authentication_failed" -> "模型服务授权失败，请检查服务器上的 DeepSeek Key。"
            "ai_provider_rejected_request" -> "模型服务拒绝了这次请求，换一种说法再试试。"
            "ai_provider_rate_limited" -> "模型那边有点忙，稍等一会儿再试。"
            "ai_provider_unavailable" -> "模型服务暂时不可用，稍后再试。"
            "ai_provider_error" -> "模型服务返回异常，稍后再试。"
            "ai_invalid_response" -> "模型回复内容异常，请稍后重试。"
            "ai_timeout" -> "模型回复超时了，稍后再试一次。"
            "ai_network_error", "ai_stream_failed", "internal_error" -> "小猫刚才没连上模型，稍后再试。"
            "network_error", "stream_interrupted" -> "网络中断，消息已保留。"
            "empty_response" -> "服务器没有返回内容，请稍后重试。"
            "invalid_stream" -> "回复数据异常，请稍后重试。"
            "http_error" -> friendlyHttpMessage(status)
            else -> friendlyHttpMessage(status)
        }
        return if (status != null && code !in USER_FRIENDLY_CODES) {
            "$message（$code / HTTP $status）"
        } else {
            message
        }
    }

    private fun friendlyHttpMessage(status: Int?): String = when (status) {
        400, 422 -> "请求内容不太对，换一种说法再试试。"
        401 -> "登录已失效，请重新登录。"
        403 -> "当前账号没有权限继续聊天，请重新登录后再试。"
        404 -> "这段聊天暂时找不到了，重新新建一段聊天吧。"
        408 -> "服务器响应超时了，稍后再试。"
        409 -> "这条消息状态不一致，请重新发送一条新的。"
        429 -> "请求有点频繁，等一会儿再试。"
        in 500..599 -> "服务器正在开小差，稍后再试。"
        else -> "聊天暂时没有完成，请稍后再试。"
    }

    private fun logWarning(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private companion object {
        const val TAG = "CatLifePet"
        const val STREAM_TIMEOUT_MS = 45_000L

        val USER_FRIENDLY_CODES = setOf(
            "invalid_access_token",
            "missing_access_token",
            "token_expired",
            "daily_quota_exceeded",
            "rate_limited",
            "turn_in_progress",
            "client_message_conflict",
            "message_retry_conflict",
            "invalid_request",
            "message_too_long",
            "conversation_not_found",
            "not_found",
            "message_finalization_conflict",
            "ai_provider_authentication_failed",
            "ai_provider_rejected_request",
            "ai_provider_rate_limited",
            "ai_provider_unavailable",
            "ai_provider_error",
            "ai_invalid_response",
            "ai_timeout",
            "ai_network_error",
            "ai_stream_failed",
            "internal_error",
            "network_error",
            "stream_interrupted",
            "empty_response",
            "invalid_stream"
        )
    }
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
