package com.example.catlifepet.server.memory

import com.example.catlifepet.server.data.MemoryRecord
import kotlinx.serialization.Serializable

@Serializable
data class CreateMemoryRequest(
    val kind: String,
    val content: String,
    val sourceConversationId: String? = null
)

@Serializable
data class MemoryResponse(
    val id: String,
    val kind: String,
    val content: String,
    val sourceConversationId: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class MemoryListResponse(val memories: List<MemoryResponse>)

internal fun MemoryRecord.toResponse() = MemoryResponse(
    id = id.toString(),
    kind = kind,
    content = content,
    sourceConversationId = sourceConversationId?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

internal enum class MemoryKind(val wireName: String) {
    NICKNAME("nickname"),
    PREFERRED_ADDRESS("preferred_address"),
    ROUTINE("routine"),
    PREFERENCE("preference");

    companion object {
        fun fromWireName(value: String) = entries.firstOrNull { it.wireName == value }
    }
}
