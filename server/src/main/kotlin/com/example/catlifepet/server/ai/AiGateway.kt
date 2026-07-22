package com.example.catlifepet.server.ai

import com.example.catlifepet.server.config.AiSettings
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class AiGateway(
    private val settings: AiSettings,
    private val provider: AiProvider
) : AutoCloseable {
    suspend fun generate(request: AiRequest): AiResult {
        validate(request)
        return try {
            withTimeout(settings.requestTimeout.toMillis()) {
                provider.generate(request)
            }
        } catch (error: TimeoutCancellationException) {
            throw AiTimeoutException()
        }
    }

    override fun close() {
        provider.close()
    }

    private fun validate(request: AiRequest) {
        if (request.instructions.isBlank()) throw AiInputException("AI instructions must not be blank.")
        if (request.messages.isEmpty()) throw AiInputException("At least one AI message is required.")
        if (request.messages.any { it.content.isBlank() }) {
            throw AiInputException("AI messages must not be blank.")
        }
        val characterCount = request.instructions.length + request.messages.sumOf { it.content.length }
        if (characterCount > settings.maximumInputCharacters) {
            throw AiInputException("AI input exceeds the configured character limit.")
        }
        if (request.safetyIdentifier.length !in 8..128 || !SAFETY_IDENTIFIER.matches(request.safetyIdentifier)) {
            throw AiInputException("AI safety identifier is invalid.")
        }
    }

    private companion object {
        val SAFETY_IDENTIFIER = Regex("^[A-Za-z0-9._:-]+$")
    }
}
