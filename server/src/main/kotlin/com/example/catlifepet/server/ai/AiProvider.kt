package com.example.catlifepet.server.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface AiProvider : AutoCloseable {
    suspend fun generate(request: AiRequest): AiResult

    fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        val result = generate(request)
        emit(AiStreamEvent.Delta(result.text))
        emit(AiStreamEvent.Completed(result))
    }

    override fun close() = Unit
}

class DeterministicFakeAiProvider : AiProvider {
    override suspend fun generate(request: AiRequest): AiResult {
        val latestMessage = request.messages.lastOrNull()?.content.orEmpty()
        return AiResult(
            providerResponseId = "fake-${latestMessage.hashCode().toUInt().toString(16)}",
            text = "我在呢。你刚才说了 ${latestMessage.length} 个字符。",
            model = "catlifepet-fake",
            usage = AiUsage(
                inputTokens = estimateTokens(request.instructions + latestMessage),
                outputTokens = 12,
                totalTokens = estimateTokens(request.instructions + latestMessage) + 12
            )
        )
    }

    private fun estimateTokens(value: String): Int = (value.length + 3) / 4
}
