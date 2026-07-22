package com.example.catlifepet.server.ai

import com.example.catlifepet.server.config.AiSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiGatewayTest {
    @Test
    fun `fake provider is deterministic and returns usage`() = runTest {
        val gateway = AiGateway(AiSettings(), DeterministicFakeAiProvider())
        val request = validRequest()

        val first = gateway.generate(request)
        val second = gateway.generate(request)

        assertEquals(first, second)
        assertEquals("catlifepet-fake", first.model)
        assertTrue(first.text.isNotBlank())
        assertTrue(first.usage!!.totalTokens > 0)
    }

    @Test
    fun `gateway rejects empty and oversized input before provider call`() = runTest {
        val provider = CountingProvider()
        val gateway = AiGateway(AiSettings(maximumInputCharacters = 256), provider)

        assertFailsWith<AiInputException> {
            gateway.generate(validRequest().copy(messages = emptyList()))
        }
        assertFailsWith<AiInputException> {
            gateway.generate(validRequest("x".repeat(300)))
        }
        assertFailsWith<AiInputException> {
            gateway.generate(validRequest().copy(safetyIdentifier = "raw user email@example.com"))
        }
        assertEquals(0, provider.calls)
    }

    @Test
    fun `gateway maps provider deadline to timeout`() = runTest {
        val provider = SuspendingProvider()
        val gateway = AiGateway(
            AiSettings(requestTimeout = Duration.ofMillis(100)),
            provider
        )

        assertFailsWith<AiTimeoutException> { gateway.generate(validRequest()) }
        assertTrue(provider.cancelled.await())
    }

    @Test
    fun `caller cancellation reaches provider unchanged`() = runTest {
        val provider = SuspendingProvider()
        val gateway = AiGateway(AiSettings(requestTimeout = Duration.ofSeconds(30)), provider)
        val job = launch { gateway.generate(validRequest()) }
        provider.started.await()

        job.cancelAndJoin()

        assertTrue(provider.cancelled.await())
        assertTrue(job.isCancelled)
    }

    private fun validRequest(content: String = "今天有点累") = AiRequest(
        instructions = "你是一只温柔、简短回应的陪伴小猫。",
        messages = listOf(AiMessage(AiRole.USER, content)),
        safetyIdentifier = "user_hash_12345678"
    )
}

private class CountingProvider : AiProvider {
    var calls = 0

    override suspend fun generate(request: AiRequest): AiResult {
        calls += 1
        return AiResult(null, "ok", "counting", null)
    }
}

private class SuspendingProvider : AiProvider {
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Boolean>()

    override suspend fun generate(request: AiRequest): AiResult {
        started.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            cancelled.complete(true)
        }
    }
}
