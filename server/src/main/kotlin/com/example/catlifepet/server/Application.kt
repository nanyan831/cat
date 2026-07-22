package com.example.catlifepet.server

import com.example.catlifepet.server.config.ServerSettings
import com.example.catlifepet.server.auth.AuthRuntimeOverrides
import com.example.catlifepet.server.auth.configureAuthentication
import com.example.catlifepet.server.ai.AiRuntimeOverrides
import com.example.catlifepet.server.ai.configureAi
import com.example.catlifepet.server.data.configureDatabase
import com.example.catlifepet.server.chat.configureChat
import com.example.catlifepet.server.memory.configureMemory
import com.example.catlifepet.server.http.configureHttp
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    module(ServerSettings.load(environment.config))
}

internal fun Application.module(
    settings: ServerSettings,
    authOverrides: AuthRuntimeOverrides = AuthRuntimeOverrides(),
    aiOverrides: AiRuntimeOverrides = AiRuntimeOverrides()
) {
    configureDatabase(settings)
    configureAi(settings, aiOverrides)
    configureHttp(settings)
    val authService = configureAuthentication(settings, authOverrides)
    if (authService != null) {
        configureChat(authService, settings.ai)
        configureMemory(authService)
    }
}
