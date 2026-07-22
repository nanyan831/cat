package com.example.catlifepet.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.example.catlifepet.server.config.AuthSettings
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID

internal data class IssuedAccessToken(
    val value: String,
    val expiresAt: Instant
)

internal class JwtService(
    private val settings: AuthSettings,
    secret: String,
    private val clock: Clock
) {
    private val algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(settings.issuer)
        .withAudience(settings.audience)
        .build()

    fun issue(userId: UUID, sessionId: UUID): IssuedAccessToken {
        val issuedAt = clock.instant()
        val expiresAt = issuedAt.plus(settings.accessTokenLifetime)
        val value = JWT.create()
            .withIssuer(settings.issuer)
            .withAudience(settings.audience)
            .withSubject(userId.toString())
            .withClaim(SESSION_ID_CLAIM, sessionId.toString())
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(expiresAt))
            .withJWTId(UUID.randomUUID().toString())
            .sign(algorithm)
        return IssuedAccessToken(value, expiresAt)
    }

    companion object {
        const val SESSION_ID_CLAIM = "sid"
    }
}
