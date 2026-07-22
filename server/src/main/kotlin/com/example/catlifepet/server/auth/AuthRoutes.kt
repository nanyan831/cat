package com.example.catlifepet.server.auth

import com.example.catlifepet.server.http.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

internal fun Application.configureAuthRoutes(authService: AuthService) {
    routing {
        route("/v1/auth") {
            post("/code/request") {
                val request = call.receive<RequestLoginCodeRequest>()
                val response = authService.requestLoginCode(request.email, call.request.origin.remoteHost)
                call.respond(HttpStatusCode.Accepted, response)
            }
            post("/code/verify") {
                call.respond(authService.verifyLoginCode(call.receive()))
            }
            post("/refresh") {
                call.respond(authService.refresh(call.receive()))
            }
        }

        authenticate(AUTH_PROVIDER) {
            post("/v1/auth/logout") {
                call.respond(authService.logout(call.requireIdentity(authService)))
            }
            get("/v1/me") {
                call.respond(call.requireIdentity(authService).user.toResponse())
            }
            patch("/v1/me") {
                val identity = call.requireIdentity(authService)
                call.respond(authService.updateProfile(identity, call.receive()))
            }
            delete("/v1/me") {
                call.respond(authService.deleteAccount(call.requireIdentity(authService)))
            }
        }
    }
}

internal suspend fun ApplicationCall.requireIdentity(authService: AuthService): AuthenticatedUser {
    val principal = principal<JWTPrincipal>() ?: throw invalidAccessToken()
    val userId = principal.payload.subject?.toUuidOrNull() ?: throw invalidAccessToken()
    val sessionId = principal.payload.getClaim(JwtService.SESSION_ID_CLAIM)
        .asString()
        ?.toUuidOrNull()
        ?: throw invalidAccessToken()
    return authService.authenticate(userId, sessionId)
}

private fun invalidAccessToken() = ApiException(
    HttpStatusCode.Unauthorized,
    "invalid_access_token",
    "The access token is invalid or expired."
)
