package com.example.catlifepet.server.ai

import com.example.catlifepet.server.config.AiBackend
import com.example.catlifepet.server.config.ServerSettings
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.util.AttributeKey

internal data class AiRuntimeOverrides(
    val provider: AiProvider? = null
)

private val AiGatewayKey = AttributeKey<AiGateway>("CatLifePetAiGateway")

val Application.aiGateway: AiGateway
    get() = attributes[AiGatewayKey]

internal fun Application.configureAi(settings: ServerSettings, overrides: AiRuntimeOverrides) {
    val provider = overrides.provider ?: when (settings.ai.backend) {
        AiBackend.FAKE -> DeterministicFakeAiProvider()
        AiBackend.OPENAI -> OpenAiResponsesProvider(
            settings = settings.ai,
            apiKey = checkNotNull(settings.sensitive.openAiApiKey)
        )
        AiBackend.DEEPSEEK -> DeepSeekChatCompletionsProvider(
            settings = settings.ai,
            apiKey = checkNotNull(settings.sensitive.deepSeekApiKey)
        )
    }
    val gateway = AiGateway(settings.ai, provider)
    attributes.put(AiGatewayKey, gateway)
    monitor.subscribe(ApplicationStopped) { gateway.close() }
    environment.log.info(
        "AI gateway ready: backend={}, model={}, storeResponses={}",
        settings.ai.backend.wireName,
        if (settings.ai.backend == AiBackend.FAKE) "catlifepet-fake" else settings.ai.model,
        settings.ai.storeResponses
    )
}
