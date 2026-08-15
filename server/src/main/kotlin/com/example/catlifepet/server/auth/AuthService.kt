package com.example.catlifepet.server.auth

import com.example.catlifepet.server.config.AuthSettings
import com.example.catlifepet.server.data.DatabaseFactory
import com.example.catlifepet.server.data.LoginCodeRecord
import com.example.catlifepet.server.data.RefreshSessionRecord
import com.example.catlifepet.server.data.Repositories
import com.example.catlifepet.server.data.UserRecord
import com.example.catlifepet.server.http.ApiException
import io.ktor.http.HttpStatusCode
import jakarta.mail.MessagingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

internal data class AuthenticatedUser(
    val user: UserRecord,
    val session: RefreshSessionRecord
)

internal class AuthService(
    private val database: DatabaseFactory,
    private val repositories: Repositories,
    private val settings: AuthSettings,
    private val crypto: AuthCrypto,
    private val jwtService: JwtService,
    private val emailSender: EmailSender,
    private val clock: Clock
) {
    suspend fun requestLoginCode(rawEmail: String, remoteAddress: String): RequestLoginCodeResponse {
        val email = normalizeEmail(rawEmail)
        val now = clock.instant()
        val code = crypto.generateVerificationCode()
        val ipHash = crypto.hashIpAddress(remoteAddress)
        val codeRecord = LoginCodeRecord(
            id = UUID.randomUUID(),
            emailNormalized = email,
            codeHash = crypto.hashVerificationCode(email, code),
            requestIpHash = ipHash,
            expiresAt = now.plus(settings.verificationCodeLifetime),
            consumedAt = null,
            failedAttempts = 0,
            createdAt = now
        )

        inTransaction { connection ->
            repositories.loginCodes.lockRequestKeys(connection, email, ipHash)
            val since = now.minus(settings.resendWindow)
            val emailCount = repositories.loginCodes.countCreatedSinceByEmail(connection, email, since)
            val ipCount = repositories.loginCodes.countCreatedSinceByIpHash(connection, ipHash, since)
            if (
                emailCount >= settings.maximumEmailRequestsPerWindow ||
                ipCount >= settings.maximumIpRequestsPerWindow
            ) {
                throw ApiException(
                    HttpStatusCode.TooManyRequests,
                    "rate_limited",
                    "Please wait before requesting another code."
                )
            }
            repositories.loginCodes.invalidateActiveForEmail(connection, email, now)
            repositories.loginCodes.insert(connection, codeRecord)
        }

        try {
            emailSender.sendLoginCode(LoginCodeEmail(email, code, codeRecord.expiresAt))
        } catch (error: MessagingException) {
            throw ApiException(
                HttpStatusCode.ServiceUnavailable,
                "code_send_failed",
                "The verification code could not be sent. Please try again later."
            )
        }
        return RequestLoginCodeResponse(expiresInSeconds = settings.verificationCodeLifetime.seconds)
    }

    suspend fun verifyLoginCode(request: VerifyLoginCodeRequest): AuthSessionResponse {
        val email = normalizeEmail(request.email)
        val code = request.code.trim()
        if (!CODE_PATTERN.matches(code)) throw invalidCode()
        val now = clock.instant()

        val outcome = inTransaction { connection ->
            val stored = repositories.loginCodes.findLatestActiveForUpdate(connection, email, now)
                ?: return@inTransaction VerifyOutcome.Invalid
            if (stored.failedAttempts >= settings.maximumCodeAttempts) {
                return@inTransaction VerifyOutcome.Locked
            }
            val suppliedHash = crypto.hashVerificationCode(email, code)
            if (!crypto.matches(stored.codeHash, suppliedHash)) {
                repositories.loginCodes.incrementFailedAttempts(connection, stored.id)
                return@inTransaction VerifyOutcome.Invalid
            }
            if (!repositories.loginCodes.markConsumed(connection, stored.id, now)) {
                return@inTransaction VerifyOutcome.Invalid
            }

            val user = repositories.users.findActiveByEmail(connection, email)
                ?: UserRecord(
                    id = UUID.randomUUID(),
                    emailNormalized = email,
                    emailDisplay = request.email.trim(),
                    displayName = null,
                    timeZone = "UTC",
                    createdAt = now,
                    updatedAt = now
                ).also { repositories.users.insert(connection, it) }
            val sessionToken = createSession(connection, user.id, null, request.deviceLabel, now)
            VerifyOutcome.Success(user, sessionToken)
        }

        return when (outcome) {
            VerifyOutcome.Invalid -> throw invalidCode()
            VerifyOutcome.Locked -> throw ApiException(
                HttpStatusCode.TooManyRequests,
                "code_attempts_exceeded",
                "Too many verification attempts. Request a new code."
            )
            is VerifyOutcome.Success -> sessionResponse(outcome.user, outcome.sessionToken, now)
        }
    }

    suspend fun refresh(request: RefreshSessionRequest): AuthSessionResponse {
        val rawToken = request.refreshToken.trim()
        if (rawToken.length !in 32..512) throw invalidSession()
        val tokenHash = crypto.hashRefreshToken(rawToken)
        val now = clock.instant()

        val outcome = inTransaction { connection ->
            val current = repositories.refreshSessions.findByTokenHashForUpdate(connection, tokenHash)
                ?: return@inTransaction RefreshOutcome.Invalid
            if (current.revokedAt != null) {
                repositories.refreshSessions.revokeFamily(connection, current.familyId, now)
                return@inTransaction RefreshOutcome.Replay
            }
            if (!current.expiresAt.isAfter(now)) {
                repositories.refreshSessions.revoke(connection, current.id, now)
                return@inTransaction RefreshOutcome.Invalid
            }
            val user = repositories.users.findById(connection, current.userId)
                ?.takeIf { it.deletedAt == null }
                ?: return@inTransaction RefreshOutcome.Invalid
            val replacement = createSession(
                connection,
                current.userId,
                current.familyId,
                request.deviceLabel ?: current.deviceLabel,
                now
            )
            check(repositories.refreshSessions.revoke(connection, current.id, now, replacement.record.id))
            RefreshOutcome.Success(user, replacement)
        }

        return when (outcome) {
            RefreshOutcome.Invalid -> throw invalidSession()
            RefreshOutcome.Replay -> throw ApiException(
                HttpStatusCode.Unauthorized,
                "session_replay_detected",
                "This session can no longer be used. Please sign in again."
            )
            is RefreshOutcome.Success -> sessionResponse(outcome.user, outcome.sessionToken, now)
        }
    }

    suspend fun authenticate(userId: UUID, sessionId: UUID): AuthenticatedUser {
        val now = clock.instant()
        return inTransaction { connection ->
            val session = repositories.refreshSessions.findById(connection, sessionId)
                ?.takeIf {
                    it.userId == userId && it.revokedAt == null && it.expiresAt.isAfter(now)
                }
                ?: throw invalidAccessToken()
            val user = repositories.users.findById(connection, userId)
                ?.takeIf { it.deletedAt == null }
                ?: throw invalidAccessToken()
            AuthenticatedUser(user, session)
        }
    }

    suspend fun logout(identity: AuthenticatedUser): ActionResponse {
        val now = clock.instant()
        inTransaction { connection ->
            repositories.refreshSessions.revoke(connection, identity.session.id, now)
        }
        return ActionResponse()
    }

    suspend fun updateProfile(
        identity: AuthenticatedUser,
        request: UpdateProfileRequest
    ): UserResponse {
        val displayName = request.displayName?.trim()?.also {
            if (it.length !in 1..80) throw invalidField("displayName")
        } ?: identity.user.displayName
        val timeZone = request.timeZone?.trim()?.also {
            if (it.length > 64 || runCatching { ZoneId.of(it) }.isFailure) throw invalidField("timeZone")
        } ?: identity.user.timeZone
        val updated = inTransaction { connection ->
            repositories.users.updateProfile(connection, identity.user.id, displayName, timeZone, clock.instant())
        } ?: throw invalidAccessToken()
        return updated.toResponse()
    }

    suspend fun deleteAccount(identity: AuthenticatedUser): ActionResponse {
        val now = clock.instant()
        inTransaction { connection ->
            repositories.refreshSessions.revokeAllForUser(connection, identity.user.id, now)
            check(repositories.users.delete(connection, identity.user.id))
        }
        return ActionResponse()
    }

    private fun createSession(
        connection: Connection,
        userId: UUID,
        familyId: UUID?,
        rawDeviceLabel: String?,
        now: Instant
    ): SessionToken {
        val rawToken = crypto.generateRefreshToken()
        val record = RefreshSessionRecord(
            id = UUID.randomUUID(),
            userId = userId,
            familyId = familyId ?: UUID.randomUUID(),
            tokenHash = crypto.hashRefreshToken(rawToken),
            deviceLabel = rawDeviceLabel?.trim()?.take(160)?.takeIf(String::isNotEmpty),
            expiresAt = now.plus(settings.refreshTokenLifetime),
            revokedAt = null,
            replacedBy = null,
            createdAt = now,
            lastUsedAt = now
        )
        repositories.refreshSessions.insert(connection, record)
        return SessionToken(rawToken, record)
    }

    private fun sessionResponse(user: UserRecord, sessionToken: SessionToken, now: Instant): AuthSessionResponse {
        val accessToken = jwtService.issue(user.id, sessionToken.record.id)
        return AuthSessionResponse(
            accessToken = accessToken.value,
            refreshToken = sessionToken.rawToken,
            expiresInSeconds = accessToken.expiresAt.epochSecond - now.epochSecond,
            user = user.toResponse()
        )
    }

    private suspend fun <T> inTransaction(block: (Connection) -> T): T {
        return withContext(Dispatchers.IO) { database.transaction(block) }
    }

    private fun normalizeEmail(rawEmail: String): String {
        val value = rawEmail.trim()
        if (value.length !in 3..320 || !EMAIL_PATTERN.matches(value)) throw invalidField("email")
        return value.lowercase(Locale.ROOT)
    }

    private fun invalidField(field: String) = ApiException(
        HttpStatusCode.BadRequest,
        "invalid_request",
        "Invalid $field."
    )

    private fun invalidCode() = ApiException(
        HttpStatusCode.Unauthorized,
        "invalid_code",
        "The verification code is invalid or expired."
    )

    private fun invalidSession() = ApiException(
        HttpStatusCode.Unauthorized,
        "invalid_session",
        "The session is invalid or expired."
    )

    private fun invalidAccessToken() = ApiException(
        HttpStatusCode.Unauthorized,
        "invalid_access_token",
        "The access token is invalid or expired."
    )

    private data class SessionToken(val rawToken: String, val record: RefreshSessionRecord)

    private sealed interface VerifyOutcome {
        data object Invalid : VerifyOutcome
        data object Locked : VerifyOutcome
        data class Success(val user: UserRecord, val sessionToken: SessionToken) : VerifyOutcome
    }

    private sealed interface RefreshOutcome {
        data object Invalid : RefreshOutcome
        data object Replay : RefreshOutcome
        data class Success(val user: UserRecord, val sessionToken: SessionToken) : RefreshOutcome
    }

    private companion object {
        val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        val CODE_PATTERN = Regex("^[0-9]{6}$")
    }
}

internal fun UserRecord.toResponse() = UserResponse(
    id = id.toString(),
    email = emailDisplay,
    displayName = displayName,
    timeZone = timeZone
)
