package com.example.catlifepet.server.http

import com.example.catlifepet.server.config.ServerSettings
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

internal fun Application.configureRoutes(settings: ServerSettings) {
    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    service = settings.serviceName,
                    version = settings.version,
                    environment = settings.environment.wireName,
                    requestId = call.requestId()
                )
            )
        }
    }
}
