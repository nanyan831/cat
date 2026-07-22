package com.example.catlifepet.chat

data class ChatConversation(
    val id: String,
    val title: String?,
    val createdAt: String,
    val updatedAt: String
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val sequenceNumber: Long,
    val role: String,
    val content: String,
    val status: String,
    val clientMessageId: String? = null,
    val pending: Boolean = false,
    val errorMessage: String? = null
)

sealed interface ChatLoadResult {
    data class Ready(val conversationId: String, val offline: Boolean = false) : ChatLoadResult
    data object SessionExpired : ChatLoadResult
    data class Failure(val message: String) : ChatLoadResult
}

sealed interface ChatSendEvent {
    data class Delta(val messageId: String, val text: String) : ChatSendEvent
    data class Completed(val message: ChatMessage) : ChatSendEvent
    data class Failure(
        val code: String,
        val message: String,
        val retryable: Boolean,
        val sessionExpired: Boolean = false
    ) : ChatSendEvent
}

interface ChatDataSource {
    fun observeConversations(): kotlinx.coroutines.flow.Flow<List<ChatConversation>>
    fun observeMessages(conversationId: String): kotlinx.coroutines.flow.Flow<List<ChatMessage>>
    suspend fun loadWorkspace(preferredConversationId: String? = null): ChatLoadResult
    suspend fun createConversation(title: String? = null): ChatLoadResult
    suspend fun selectConversation(conversationId: String): ChatLoadResult
    fun sendMessage(conversationId: String, content: String, clientMessageId: String): kotlinx.coroutines.flow.Flow<ChatSendEvent>
    suspend fun deleteConversation(conversationId: String): ChatLoadResult
}
