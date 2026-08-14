package com.example.catlifepet.server.ai

import com.example.catlifepet.server.config.AiBackend
import com.example.catlifepet.server.config.AiSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class DeepSeekChatCompletionsProviderTest {
    @Test
    fun `provider sends chat completions request and parses text usage`() = runBlocking {
        var authorization: String? = null
        var requestJson = ""
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            requestJson = (request.body as TextContent).text
            respond(successResponse(), HttpStatusCode.OK, jsonHeaders())
        }

        val result = provider(HttpClient(engine)).generate(validRequest())

        val body = Json.parseToJsonElement(requestJson).jsonObject
        assertEquals("Bearer test-deepseek-key", authorization)
        assertEquals(AiSettings.DEFAULT_DEEPSEEK_MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals(500, body["max_tokens"]!!.jsonPrimitive.int)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Be a gentle companion.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("我在听。", result.text)
        assertEquals(AiSettings.DEFAULT_DEEPSEEK_MODEL, result.model)
        assertEquals("chatcmpl_test", result.providerResponseId)
        assertEquals(18, result.usage!!.totalTokens)
    }

    @Test
    fun `provider streams ordered deltas and emits completion`() = runBlocking {
        var requestJson = ""
        val engine = MockEngine { request ->
            requestJson = (request.body as TextContent).text
            respond(
                content = buildString {
                    appendLine("data: {\"id\":\"chatcmpl_stream\",\"model\":\"${AiSettings.DEFAULT_DEEPSEEK_MODEL}\",\"choices\":[{\"delta\":{\"content\":\"我在\"},\"finish_reason\":null}]}")
                    appendLine()
                    appendLine("data: {\"id\":\"chatcmpl_stream\",\"model\":\"${AiSettings.DEFAULT_DEEPSEEK_MODEL}\",\"choices\":[{\"delta\":{\"content\":\"听。\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":8,\"total_tokens\":18}}")
                    appendLine()
                    appendLine("data: [DONE]")
                    appendLine()
                },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val events = provider(HttpClient(engine)).stream(validRequest()).toList()

        val body = Json.parseToJsonElement(requestJson).jsonObject
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("我在", "听。"), events.filterIsInstance<AiStreamEvent.Delta>().map { it.text })
        val completed = events.filterIsInstance<AiStreamEvent.Completed>().single().result
        assertEquals("我在听。", completed.text)
        assertEquals("chatcmpl_stream", completed.providerResponseId)
        assertEquals(18, completed.usage!!.totalTokens)
    }

    @Test
    fun `provider maps authentication balance and rate failures`() = runBlocking {
        val authProvider = provider(clientResponding(HttpStatusCode.Unauthorized, "secret auth detail"))
        val authError = assertFailsWith<AiProviderException> { authProvider.generate(validRequest()) }
        assertEquals("ai_provider_authentication_failed", authError.code)
        assertFalse(authError.retryable)
        assertFalse(authError.message.orEmpty().contains("secret auth detail"))

        val balanceProvider = provider(clientResponding(HttpStatusCode.PaymentRequired, "balance detail"))
        val balanceError = assertFailsWith<AiProviderException> { balanceProvider.generate(validRequest()) }
        assertEquals("ai_provider_insufficient_balance", balanceError.code)
        assertFalse(balanceError.retryable)
        assertFalse(balanceError.message.orEmpty().contains("balance detail"))

        val rateProvider = provider(clientResponding(HttpStatusCode.TooManyRequests, "rate body"))
        val rateError = assertFailsWith<AiProviderException> { rateProvider.generate(validRequest()) }
        assertEquals("ai_provider_rate_limited", rateError.code)
        assertTrue(rateError.retryable)
    }

    @Test
    fun `provider maps server and malformed responses to retryable failures`() = runBlocking {
        val unavailable = provider(clientResponding(HttpStatusCode.ServiceUnavailable, "down"))
        val unavailableError = assertFailsWith<AiProviderException> { unavailable.generate(validRequest()) }
        assertEquals("ai_provider_unavailable", unavailableError.code)
        assertTrue(unavailableError.retryable)

        val malformed = provider(clientResponding(HttpStatusCode.OK, "not-json"))
        val malformedError = assertFailsWith<AiProviderException> { malformed.generate(validRequest()) }
        assertEquals("ai_invalid_response", malformedError.code)
        assertTrue(malformedError.retryable)
    }

    @Test
    fun `optional real DeepSeek smoke test`() = runBlocking {
        val apiKey = System.getenv("DEEPSEEK_API_KEY")
        val enabled = System.getenv("CATLIFEPET_RUN_DEEPSEEK_SMOKE") == "true"
        if (apiKey.isNullOrBlank() || !enabled) return@runBlocking

        val provider = DeepSeekChatCompletionsProvider(
            settings = settings().copy(
                model = System.getenv("DEEPSEEK_MODEL") ?: AiSettings.DEFAULT_DEEPSEEK_MODEL,
                deepSeekBaseUrl = System.getenv("DEEPSEEK_BASE_URL") ?: AiSettings.DEFAULT_DEEPSEEK_BASE_URL
            ),
            apiKey = apiKey
        )
        try {
            val result = provider.generate(validRequest())
            assertTrue(result.text.isNotBlank())
            assertNotNull(result.model)
        } finally {
            provider.close()
        }
    }

    private fun provider(client: HttpClient): DeepSeekChatCompletionsProvider {
        return DeepSeekChatCompletionsProvider(settings(), "test-deepseek-key", client, ownsClient = false)
    }

    private fun settings() = AiSettings(
        backend = AiBackend.DEEPSEEK,
        model = AiSettings.DEFAULT_DEEPSEEK_MODEL,
        deepSeekBaseUrl = "https://deepseek.test"
    )

    private fun validRequest() = AiRequest(
        instructions = "Be a gentle companion.",
        messages = listOf(AiMessage(AiRole.USER, "你好")),
        safetyIdentifier = "safe_user_123456"
    )

    private fun clientResponding(status: HttpStatusCode, body: String): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { respond(body, status, jsonHeaders()) }
            }
        }
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun successResponse() = """
        {
          "id":"chatcmpl_test",
          "model":"${AiSettings.DEFAULT_DEEPSEEK_MODEL}",
          "choices":[
            {"index":0,"message":{"role":"assistant","content":"我在听。"},"finish_reason":"stop"}
          ],
          "usage":{"prompt_tokens":10,"completion_tokens":8,"total_tokens":18}
        }
    """.trimIndent()
}
