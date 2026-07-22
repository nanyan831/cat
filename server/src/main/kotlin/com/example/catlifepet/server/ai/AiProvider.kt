package com.example.catlifepet.server.ai

interface AiProvider : AutoCloseable {
    suspend fun generate(request: AiRequest): AiResult

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
