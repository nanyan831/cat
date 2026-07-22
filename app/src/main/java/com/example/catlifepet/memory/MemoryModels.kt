package com.example.catlifepet.memory

data class CompanionMemory(
    val id: String,
    val kind: String,
    val content: String,
    val createdAt: String,
    val updatedAt: String
)

sealed interface MemoryOutcome<out T> {
    data class Success<T>(val value: T) : MemoryOutcome<T>
    data object SessionExpired : MemoryOutcome<Nothing>
    data class Failure(val message: String) : MemoryOutcome<Nothing>
}

interface MemoryDataSource {
    suspend fun list(): MemoryOutcome<List<CompanionMemory>>
    suspend fun create(kind: String, content: String): MemoryOutcome<CompanionMemory>
    suspend fun delete(memoryId: String): MemoryOutcome<Unit>
    suspend fun deleteAll(): MemoryOutcome<Unit>
    suspend fun clearConversations(): MemoryOutcome<Unit>
}
