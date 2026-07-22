package com.example.catlifepet.server.memory

import com.example.catlifepet.server.auth.AUTH_PROVIDER
import com.example.catlifepet.server.auth.AuthService
import com.example.catlifepet.server.auth.requireIdentity
import com.example.catlifepet.server.auth.toUuidOrNull
import com.example.catlifepet.server.data.databaseContext
import com.example.catlifepet.server.http.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

internal fun Application.configureMemory(authService: AuthService) {
    val context = checkNotNull(databaseContext)
    val service = MemoryService(context.database, context.repositories)
    routing {
        authenticate(AUTH_PROVIDER) {
            route("/v1/memories") {
                get {
                    val identity = call.requireIdentity(authService)
                    call.respond(service.list(identity.user.id))
                }
                post {
                    val identity = call.requireIdentity(authService)
                    call.respond(HttpStatusCode.Created, service.create(identity.user.id, call.receive()))
                }
                delete {
                    val identity = call.requireIdentity(authService)
                    service.deleteAll(identity.user.id)
                    call.respond(HttpStatusCode.NoContent)
                }
                delete("/{memoryId}") {
                    val identity = call.requireIdentity(authService)
                    val memoryId = call.parameters["memoryId"]?.toUuidOrNull()
                        ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "memoryId must be a UUID.")
                    service.delete(identity.user.id, memoryId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
    environment.log.info("User-controlled memory routes ready")
}
