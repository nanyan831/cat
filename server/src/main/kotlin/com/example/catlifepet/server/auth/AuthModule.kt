package com.example.catlifepet.server.auth

import com.example.catlifepet.server.config.AppEnvironment
import com.example.catlifepet.server.config.ServerSettings
import com.example.catlifepet.server.data.databaseContext
import com.example.catlifepet.server.http.ApiError
import com.example.catlifepet.server.http.ApiErrorEnvelope
import com.example.catlifepet.server.http.requestId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

internal data class AuthRuntimeOverrides(
    val emailSender: EmailSender? = null,
    val clock: Clock = Clock.systemUTC(),
    val secureRandom: SecureRandom = SecureRandom()
)

internal fun Application.configureAuthentication(
    settings: ServerSettings,
    overrides: AuthRuntimeOverrides
): AuthService? {
    val context = databaseContext ?: run {
        environment.log.info("Authentication routes disabled because no database is configured")
        return null
    }
    val jwtSecret = checkNotNull(settings.sensitive.jwtSecret)
    val tokenPepper = checkNotNull(settings.sensitive.tokenPepper)
    val emailSender = overrides.emailSender
        ?: settings.sensitive.smtp?.let(::SmtpEmailSender)
        ?: when (settings.environment) {
            AppEnvironment.PRODUCTION -> error("Production SMTP configuration was not validated")
            else -> DevelopmentMailboxEmailSender(Path.of(settings.developmentMailboxDir))
        }
    val jwtService = JwtService(settings.auth, jwtSecret, overrides.clock)
    val authService = AuthService(
        database = context.database,
        repositories = context.repositories,
        settings = settings.auth,
        crypto = AuthCrypto(tokenPepper, overrides.secureRandom),
        jwtService = jwtService,
        emailSender = emailSender,
        clock = overrides.clock
    )

    install(Authentication) {
        jwt(AUTH_PROVIDER) {
            realm = "CatLifePet"
            verifier(jwtService.verifier)
            validate { credential ->
                val userId = credential.payload.subject?.toUuidOrNull()
                val sessionId = credential.payload.getClaim(JwtService.SESSION_ID_CLAIM)
                    .asString()
                    ?.toUuidOrNull()
                if (
                    userId != null &&
                    sessionId != null &&
                    credential.payload.audience.contains(settings.auth.audience)
                ) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorEnvelope(
                        ApiError("invalid_access_token", "The access token is invalid or expired."),
                        call.requestId()
                    )
                )
            }
        }
    }

    configureAuthRoutes(authService)
    return authService
}

internal const val AUTH_PROVIDER = "catlifepet-access-token"

internal fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
