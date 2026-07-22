package com.example.catlifepet.server.chat

import com.example.catlifepet.server.ai.aiGateway
import com.example.catlifepet.server.auth.AuthService
import com.example.catlifepet.server.config.AiSettings
import com.example.catlifepet.server.data.databaseContext
import io.ktor.server.application.Application

internal fun Application.configureChat(authService: AuthService, settings: AiSettings) {
    val context = checkNotNull(databaseContext)
    configureChatRoutes(
        authService,
        ChatService(context.database, context.repositories, aiGateway, settings)
    )
    environment.log.info("Conversation and SSE chat routes ready")
}
