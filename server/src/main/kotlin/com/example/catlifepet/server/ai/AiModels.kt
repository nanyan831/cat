package com.example.catlifepet.server.ai

enum class AiRole(val wireName: String) {
    USER("user"),
    ASSISTANT("assistant")
}

data class AiMessage(
    val role: AiRole,
    val content: String
)

data class AiRequest(
    val instructions: String,
    val messages: List<AiMessage>,
    val safetyIdentifier: String
)

data class AiUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int
)

data class AiResult(
    val providerResponseId: String?,
    val text: String,
    val model: String,
    val usage: AiUsage?
)

sealed interface AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent
    data class Completed(val result: AiResult) : AiStreamEvent
}

sealed class AiException(
    val code: String,
    val retryable: Boolean,
    message: String
) : RuntimeException(message)

class AiInputException(message: String) : AiException("ai_invalid_input", false, message)

class AiTimeoutException : AiException(
    "ai_timeout",
    true,
    "The AI provider did not respond in time."
)

class AiProviderException(
    code: String,
    retryable: Boolean,
    message: String = "The AI provider could not complete the request."
) : AiException(code, retryable, message)

class AiRefusalException : AiException(
    "ai_refused",
    false,
    "The AI provider declined to answer."
)
