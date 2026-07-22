package com.example.catlifepet.server.chat

import com.example.catlifepet.server.data.ConversationRecord
import com.example.catlifepet.server.data.MessageRecord
import kotlinx.serialization.Serializable

@Serializable
data class CreateConversationRequest(val title: String? = null)

@Serializable
data class SendMessageRequest(
    val content: String,
    val clientMessageId: String
)

@Serializable
data class ConversationResponse(
    val id: String,
    val title: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class ConversationListResponse(val conversations: List<ConversationResponse>)

@Serializable
data class MessageResponse(
    val id: String,
    val sequenceNumber: Long,
    val role: String,
    val content: String,
    val status: String,
    val clientMessageId: String? = null,
    val replyToMessageId: String? = null,
    val model: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class MessageListResponse(val messages: List<MessageResponse>)

@Serializable
data class ChatStreamPayload(
    val messageId: String? = null,
    val delta: String? = null,
    val message: MessageResponse? = null,
    val code: String? = null,
    val error: String? = null,
    val retryable: Boolean? = null
)

internal fun ConversationRecord.toResponse() = ConversationResponse(
    id = id.toString(),
    title = title,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

internal fun MessageRecord.toResponse() = MessageResponse(
    id = id.toString(),
    sequenceNumber = sequenceNumber,
    role = role.wireName,
    content = content,
    status = status.wireName,
    clientMessageId = clientMessageId?.toString(),
    replyToMessageId = replyToMessageId?.toString(),
    model = model,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

internal sealed interface ChatStreamEvent {
    val eventName: String

    data class Delta(val messageId: String, val text: String) : ChatStreamEvent {
        override val eventName = "delta"
    }

    data class Completed(val message: MessageResponse) : ChatStreamEvent {
        override val eventName = "completed"
    }

    data class Error(val code: String, val message: String, val retryable: Boolean) : ChatStreamEvent {
        override val eventName = "error"
    }
}
