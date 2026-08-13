package com.example.catlifepet.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversations(conversations: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPending(message: PendingMessageEntity)

    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC, id")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC, id")
    suspend fun listConversations(): List<ConversationEntity>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY sequenceNumber")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY sequenceNumber")
    suspend fun listMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM pending_chat_messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMillis")
    fun observePending(conversationId: String): Flow<List<PendingMessageEntity>>

    @Query("SELECT * FROM pending_chat_messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMillis")
    suspend fun listPending(conversationId: String): List<PendingMessageEntity>

    @Query("DELETE FROM pending_chat_messages WHERE clientMessageId = :clientMessageId")
    suspend fun deletePending(clientMessageId: String)

    @Query("DELETE FROM pending_chat_messages")
    suspend fun deleteAllPending()

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM chat_conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM chat_conversations WHERE id NOT IN (:activeIds)")
    suspend fun deleteConversationsNotIn(activeIds: List<String>)

    @Query("DELETE FROM chat_conversations")
    suspend fun deleteAllConversations()

    @Transaction
    suspend fun clearLocalChatCache() {
        deleteAllPending()
        deleteAllMessages()
        deleteAllConversations()
    }

    @Transaction
    suspend fun replaceConversations(conversations: List<ConversationEntity>) {
        if (conversations.isEmpty()) clearLocalChatCache()
        else deleteConversationsNotIn(conversations.map { it.id })
        upsertConversations(conversations)
    }

    @Transaction
    suspend fun replaceServerHistory(conversationId: String, messages: List<MessageEntity>) {
        deleteMessages(conversationId)
        upsertMessages(messages)
    }
}
