package com.example.catlifepet.server.ai

import com.example.catlifepet.server.config.AiSettings
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

class AiGateway(
    private val settings: AiSettings,
    private val provider: AiProvider,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val circuitLock = Any()
    private var consecutiveFailures = 0
    private var circuitOpenUntil = 0L

    suspend fun generate(request: AiRequest): AiResult {
        validate(request)
        ensureCircuitClosed()
        return try {
            withTimeout(settings.requestTimeout.toMillis()) {
                provider.generate(request)
            }.also { recordSuccess() }
        } catch (error: TimeoutCancellationException) {
            recordFailure()
            throw AiTimeoutException()
        } catch (error: AiException) {
            if (error.retryable) recordFailure()
            throw error
        }
    }

    fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        validate(request)
        ensureCircuitClosed()
        try {
            withTimeout(settings.requestTimeout.toMillis()) {
                provider.stream(request).collect {
                    if (it is AiStreamEvent.Completed) recordSuccess()
                    emit(it)
                }
            }
        } catch (error: TimeoutCancellationException) {
            recordFailure()
            throw AiTimeoutException()
        } catch (error: AiException) {
            if (error.retryable) recordFailure()
            throw error
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

    private fun ensureCircuitClosed() = synchronized(circuitLock) {
        val now = nowMillis()
        if (circuitOpenUntil > now) throw AiProviderException("ai_circuit_open", true)
        if (circuitOpenUntil != 0L) {
            circuitOpenUntil = 0L
            consecutiveFailures = 0
        }
    }

    private fun recordSuccess() = synchronized(circuitLock) {
        consecutiveFailures = 0
        circuitOpenUntil = 0L
    }

    private fun recordFailure() = synchronized(circuitLock) {
        consecutiveFailures += 1
        if (consecutiveFailures >= settings.circuitFailureThreshold) {
            circuitOpenUntil = nowMillis() + settings.circuitOpenDuration.toMillis()
        }
    }

    private companion object {
        val SAFETY_IDENTIFIER = Regex("^[A-Za-z0-9._:-]+$")
    }
}
