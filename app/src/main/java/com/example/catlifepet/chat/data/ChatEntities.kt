package com.example.catlifepet.chat.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["conversationId", "sequenceNumber"], unique = true)]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val sequenceNumber: Long,
    val role: String,
    val content: String,
    val status: String,
    val clientMessageId: String?,
    val replyToMessageId: String?,
    val model: String?,
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "pending_chat_messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversationId")]
)
data class PendingMessageEntity(
    @PrimaryKey val clientMessageId: String,
    val conversationId: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val state: String,
    val errorMessage: String? = null
)
