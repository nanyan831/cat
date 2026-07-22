package com.example.catlifepet.server.ai

import com.example.catlifepet.server.config.AiSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.ktor.utils.io.readUTF8Line
import java.io.IOException
import java.net.SocketTimeoutException

class OpenAiResponsesProvider internal constructor(
    private val settings: AiSettings,
    private val apiKey: String,
    private val client: HttpClient,
    private val ownsClient: Boolean
) : AiProvider {
    constructor(settings: AiSettings, apiKey: String) : this(
        settings = settings,
        apiKey = apiKey,
        client = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = settings.requestTimeout.toMillis()
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = settings.requestTimeout.toMillis()
            }
        },
        ownsClient = true
    )

    override suspend fun generate(request: AiRequest): AiResult {
        try {
            val response = client.post("${settings.openAiBaseUrl}/responses") {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody(request).toString())
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw mapHttpFailure(response.status)
            return parseResponse(body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AiException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw AiTimeoutException()
        } catch (error: SocketTimeoutException) {
            throw AiTimeoutException()
        } catch (error: IOException) {
            throw AiProviderException("ai_provider_unavailable", true)
        } catch (error: SerializationException) {
            throw AiProviderException("ai_invalid_response", true)
        } catch (error: IllegalArgumentException) {
            throw AiProviderException("ai_invalid_response", true)
        }
    }

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        try {
            client.preparePost("${settings.openAiBaseUrl}/responses") {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody(request, stream = true).toString())
            }.execute { response ->
                if (!response.status.isSuccess()) throw mapHttpFailure(response.status)
                val channel = response.bodyAsChannel()
                var completed = false
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val event = Json.parseToJsonElement(payload).jsonObject
                    when (event.string("type")) {
                        "response.output_text.delta" -> {
                            event.string("delta")?.takeIf(String::isNotEmpty)?.let {
                                emit(AiStreamEvent.Delta(it))
                            }
                        }
                        "response.completed" -> {
                            val result = parseResponse(event["response"]?.toString() ?: payload)
                            emit(AiStreamEvent.Completed(result))
                            completed = true
                        }
                        "response.failed", "error" -> {
                            throw AiProviderException("ai_provider_error", true)
                        }
                    }
                }
                if (!completed) throw AiProviderException("ai_stream_incomplete", true)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AiException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw AiTimeoutException()
        } catch (error: SocketTimeoutException) {
            throw AiTimeoutException()
        } catch (error: IOException) {
            throw AiProviderException("ai_provider_unavailable", true)
        } catch (error: SerializationException) {
            throw AiProviderException("ai_invalid_response", true)
        } catch (error: IllegalArgumentException) {
            throw AiProviderException("ai_invalid_response", true)
        }
    }

    override fun close() {
        if (ownsClient) client.close()
    }

    private fun requestBody(request: AiRequest, stream: Boolean = false): JsonObject = buildJsonObject {
        put("model", settings.model)
        put("instructions", request.instructions)
        put("input", buildJsonArray {
            request.messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role.wireName)
                    put("content", message.content)
                })
            }
        })
        put("max_output_tokens", settings.maximumOutputTokens)
        put("store", settings.storeResponses)
        put("safety_identifier", request.safetyIdentifier)
        if (stream) put("stream", true)
    }

    private fun parseResponse(body: String): AiResult {
        val root = Json.parseToJsonElement(body).jsonObject
        val contentItems = (root["output"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.flatMap { it.contentItems() }
            .orEmpty()
        val refusal = contentItems.firstOrNull { it.string("type") == "refusal" }
        if (refusal != null) throw AiRefusalException()

        val text = contentItems
            .filter { it.string("type") == "output_text" }
            ?.mapNotNull { it.string("text") }
            ?.joinToString("")
            ?.trim()
            .orEmpty()
        if (text.isBlank()) throw AiProviderException("ai_invalid_response", true)

        val usageObject = root["usage"] as? JsonObject
        val usage = usageObject?.let {
            val input = it.int("input_tokens") ?: return@let null
            val output = it.int("output_tokens") ?: return@let null
            AiUsage(input, output, it.int("total_tokens") ?: input + output)
        }
        return AiResult(
            providerResponseId = root.string("id"),
            text = text,
            model = root.string("model") ?: settings.model,
            usage = usage
        )
    }

    private fun mapHttpFailure(status: HttpStatusCode): AiException = when (status.value) {
        400, 404, 422 -> AiProviderException("ai_provider_rejected_request", false)
        401, 403 -> AiProviderException("ai_provider_authentication_failed", false)
        408 -> AiTimeoutException()
        429 -> AiProviderException("ai_provider_rate_limited", true)
        in 500..599 -> AiProviderException("ai_provider_unavailable", true)
        else -> AiProviderException("ai_provider_error", false)
    }

    private fun JsonObject.contentItems(): List<JsonObject> {
        return (this["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    private fun JsonObject.string(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.int(key: String): Int? {
        return this[key]?.jsonPrimitive?.intOrNull
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
    }
}
