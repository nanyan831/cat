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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiResponsesProviderTest {
    @Test
    fun `provider streams ordered deltas and completion`() = runBlocking {
        var requestJson = ""
        val completedResponse = Json.parseToJsonElement(successResponse()).toString()
        val engine = MockEngine { request ->
            requestJson = (request.body as TextContent).text
            respond(
                content = buildString {
                    appendLine("event: response.output_text.delta")
                    appendLine("data: {\"type\":\"response.output_text.delta\",\"delta\":\"我在\"}")
                    appendLine()
                    appendLine("event: response.output_text.delta")
                    appendLine("data: {\"type\":\"response.output_text.delta\",\"delta\":\"听。\"}")
                    appendLine()
                    appendLine("event: response.completed")
                    appendLine("data: {\"type\":\"response.completed\",\"response\":$completedResponse}")
                    appendLine()
                    appendLine("data: [DONE]")
                    appendLine()
                },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val events = provider(HttpClient(engine)).stream(validRequest()).toList()

        assertTrue(Json.parseToJsonElement(requestJson).jsonObject["stream"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("我在", "听。"), events.filterIsInstance<AiStreamEvent.Delta>().map { it.text })
        assertEquals("我在听。", events.filterIsInstance<AiStreamEvent.Completed>().single().result.text)
    }

    @Test
    fun `provider sends bounded private request and parses text usage`() = runBlocking {
        var authorization: String? = null
        var requestJson = ""
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            requestJson = (request.body as TextContent).text
            respond(
                content = successResponse(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders()
            )
        }
        val provider = provider(HttpClient(engine))

        val result = provider.generate(validRequest())

        val body = Json.parseToJsonElement(requestJson).jsonObject
        assertEquals("Bearer test-api-key", authorization)
        assertEquals(AiSettings.DEFAULT_OPENAI_MODEL, body["model"]!!.jsonPrimitive.content)
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertEquals(500, body["max_output_tokens"]!!.jsonPrimitive.int)
        assertEquals("safe_user_123456", body["safety_identifier"]!!.jsonPrimitive.content)
        assertEquals("user", body["input"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("我在听。", result.text)
        assertEquals("resp_test", result.providerResponseId)
        assertEquals(18, result.usage!!.totalTokens)
    }

    @Test
    fun `provider maps authentication and rate failures without exposing body`() = runBlocking {
        val authProvider = provider(clientResponding(HttpStatusCode.Unauthorized, "secret provider detail"))
        val authError = assertFailsWith<AiProviderException> { authProvider.generate(validRequest()) }
        assertEquals("ai_provider_authentication_failed", authError.code)
        assertFalse(authError.retryable)
        assertFalse(authError.message.orEmpty().contains("secret provider detail"))

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
    fun `provider reports refusal separately`() = runBlocking {
        val response = """
            {
              "id":"resp_refusal",
              "model":"gpt-5.6-luna",
              "output":[{"type":"message","content":[{"type":"refusal","refusal":"cannot answer"}]}]
            }
        """.trimIndent()
        val provider = provider(clientResponding(HttpStatusCode.OK, response))

        val error = assertFailsWith<AiRefusalException> { provider.generate(validRequest()) }

        assertEquals("ai_refused", error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun `provider maps network failures without leaking transport details`() = runBlocking {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { throw IOException("private network detail") }
            }
        }
        val provider = provider(client)

        val error = assertFailsWith<AiProviderException> { provider.generate(validRequest()) }

        assertEquals("ai_provider_unavailable", error.code)
        assertTrue(error.retryable)
        assertFalse(error.message.orEmpty().contains("private network detail"))
    }

    @Test
    fun `optional real provider smoke test`() = runBlocking {
        val apiKey = System.getenv("OPENAI_API_KEY")
        val enabled = System.getenv("CATLIFEPET_RUN_OPENAI_SMOKE") == "true"
        assumeTrue(enabled && !apiKey.isNullOrBlank())
        val settings = AiSettings(
            backend = AiBackend.OPENAI,
            model = System.getenv("OPENAI_MODEL") ?: AiSettings.DEFAULT_OPENAI_MODEL,
            storeResponses = false,
            requestTimeout = Duration.ofSeconds(60),
            maximumOutputTokens = 32
        )
        val provider = OpenAiResponsesProvider(settings, apiKey!!)
        try {
            val result = provider.generate(
                AiRequest(
                    instructions = "Reply briefly and plainly.",
                    messages = listOf(AiMessage(AiRole.USER, "Reply with OK.")),
                    safetyIdentifier = "catlifepet_real_smoke"
                )
            )
            assertTrue(result.text.isNotBlank())
        } finally {
            provider.close()
        }
    }

    private fun provider(client: HttpClient): OpenAiResponsesProvider {
        return OpenAiResponsesProvider(settings(), "test-api-key", client, ownsClient = false)
    }

    private fun settings() = AiSettings(
        backend = AiBackend.OPENAI,
        model = AiSettings.DEFAULT_OPENAI_MODEL,
        openAiBaseUrl = "https://openai.test/v1",
        storeResponses = false
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
          "id":"resp_test",
          "status":"completed",
          "model":"gpt-5.6-luna",
          "output":[
            {
              "type":"message",
              "role":"assistant",
              "content":[{"type":"output_text","text":"我在听。","annotations":[]}]
            }
          ],
          "usage":{"input_tokens":10,"output_tokens":8,"total_tokens":18}
        }
    """.trimIndent()
}
