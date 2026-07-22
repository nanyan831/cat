package com.example.catlifepet.chat.network

data class CreateConversationRequest(val title: String? = null)
data class SendMessageRequest(val content: String, val clientMessageId: String)

data class ConversationDto(
    val id: String,
    val title: String? = null,
    val createdAt: String,
    val updatedAt: String
)

data class ConversationListDto(val conversations: List<ConversationDto>)

data class MessageDto(
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

data class MessageListDto(val messages: List<MessageDto>)

data class ChatStreamPayloadDto(
    val messageId: String? = null,
    val delta: String? = null,
    val message: MessageDto? = null,
    val code: String? = null,
    val error: String? = null,
    val retryable: Boolean? = null
)

data class ApiErrorEnvelopeDto(val error: ApiErrorDto)
data class ApiErrorDto(val code: String, val message: String)

sealed interface TransportStreamEvent {
    data class Delta(val messageId: String, val text: String) : TransportStreamEvent
    data class Completed(val message: MessageDto) : TransportStreamEvent
    data class Failure(val status: Int?, val code: String, val message: String, val retryable: Boolean) : TransportStreamEvent
}

class ChatHttpException(
    val status: Int?,
    val code: String,
    override val message: String,
    val retryable: Boolean
) : java.io.IOException(message)
