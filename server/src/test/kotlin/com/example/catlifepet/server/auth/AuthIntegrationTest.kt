package com.example.catlifepet.server.auth

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.catlifepet.server.config.AuthSettings
import com.example.catlifepet.server.config.DatabaseSettings
import com.example.catlifepet.server.config.ServerSettings
import com.example.catlifepet.server.http.ApiErrorEnvelope
import com.example.catlifepet.server.module
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.mail.MessagingException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthIntegrationTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var databaseSettings: DatabaseSettings

    @BeforeAll
    fun startPostgres() {
        postgres = EmbeddedPostgres.builder()
            .setRegisterShutdownHook(false)
            .start()
        databaseSettings = DatabaseSettings(
            jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
            user = "postgres",
            password = "postgres"
        )
    }

    @AfterAll
    fun stopPostgres() {
        postgres.close()
    }

    @Test
    fun `valid code creates account and authenticated profile can update and logout`() = testApplication {
        val sender = RecordingEmailSender()
        val clock = MutableClock(BASE_TIME)
        application { module(testSettings(), AuthRuntimeOverrides(sender, clock)) }
        val client = jsonClient()
        val email = uniqueEmail("valid")

        assertEquals(HttpStatusCode.Accepted, client.requestCode(email).status)
        val session = client.verifyCode(email, sender.codeFor(email)).body<AuthSessionResponse>()
        assertEquals(email, session.user.email)
        assertEquals(900, session.expiresInSeconds)

        val me = client.get("/v1/me") { bearerAuth(session.accessToken) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(session.user.id, me.body<UserResponse>().id)

        val updated = client.patch("/v1/me") {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(displayName = "Momo", timeZone = "Asia/Shanghai"))
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("Momo", updated.body<UserResponse>().displayName)
        assertEquals("Asia/Shanghai", updated.body<UserResponse>().timeZone)

        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/auth/logout") { bearerAuth(session.accessToken) }.status
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/me") { bearerAuth(session.accessToken) }.status
        )
    }

    @Test
    fun `incorrect expired and reused codes are rejected`() = testApplication {
        val sender = RecordingEmailSender()
        val clock = MutableClock(BASE_TIME)
        application { module(testSettings(), AuthRuntimeOverrides(sender, clock)) }
        val client = jsonClient()

        val reusableEmail = uniqueEmail("code")
        client.requestCode(reusableEmail)
        val validCode = sender.codeFor(reusableEmail)
        val incorrect = client.verifyCode(reusableEmail, differentCode(validCode))
        assertEquals(HttpStatusCode.Unauthorized, incorrect.status)
        assertEquals("invalid_code", incorrect.body<ApiErrorEnvelope>().error.code)

        assertEquals(HttpStatusCode.OK, client.verifyCode(reusableEmail, validCode).status)
        assertEquals(HttpStatusCode.Unauthorized, client.verifyCode(reusableEmail, validCode).status)

        val expiredEmail = uniqueEmail("expired")
        client.requestCode(expiredEmail)
        val expiredCode = sender.codeFor(expiredEmail)
        clock.advance(Duration.ofMinutes(11))
        val expired = client.verifyCode(expiredEmail, expiredCode)
        assertEquals(HttpStatusCode.Unauthorized, expired.status)
        assertEquals("invalid_code", expired.body<ApiErrorEnvelope>().error.code)
    }

    @Test
    fun `verification attempts and resend requests are rate limited`() = testApplication {
        val sender = RecordingEmailSender()
        val clock = MutableClock(BASE_TIME)
        application { module(testSettings(), AuthRuntimeOverrides(sender, clock)) }
        val client = jsonClient()

        val attemptEmail = uniqueEmail("attempts")
        client.requestCode(attemptEmail)
        val actualCode = sender.codeFor(attemptEmail)
        repeat(5) {
            assertEquals(HttpStatusCode.Unauthorized, client.verifyCode(attemptEmail, differentCode(actualCode)).status)
        }
        val locked = client.verifyCode(attemptEmail, actualCode)
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertEquals("code_attempts_exceeded", locked.body<ApiErrorEnvelope>().error.code)

        val resendEmail = uniqueEmail("resend")
        repeat(3) { assertEquals(HttpStatusCode.Accepted, client.requestCode(resendEmail).status) }
        val limited = client.requestCode(resendEmail)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("rate_limited", limited.body<ApiErrorEnvelope>().error.code)
    }

    @Test
    fun `refresh rotates token and replay revokes the token family`() = testApplication {
        val sender = RecordingEmailSender()
        val clock = MutableClock(BASE_TIME)
        application { module(testSettings(), AuthRuntimeOverrides(sender, clock)) }
        val client = jsonClient()
        val email = uniqueEmail("refresh")

        client.requestCode(email)
        val first = client.verifyCode(email, sender.codeFor(email)).body<AuthSessionResponse>()
        val rotatedResponse = client.refresh(first.refreshToken)
        assertEquals(HttpStatusCode.OK, rotatedResponse.status)
        val rotated = rotatedResponse.body<AuthSessionResponse>()
        assertNotEquals(first.refreshToken, rotated.refreshToken)

        val replay = client.refresh(first.refreshToken)
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertEquals("session_replay_detected", replay.body<ApiErrorEnvelope>().error.code)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(rotated.refreshToken).status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/me") { bearerAuth(rotated.accessToken) }.status
        )
    }

    @Test
    fun `account deletion revokes access and removes server data`() = testApplication {
        val sender = RecordingEmailSender()
        val clock = MutableClock(BASE_TIME)
        application { module(testSettings(), AuthRuntimeOverrides(sender, clock)) }
        val client = jsonClient()
        val email = uniqueEmail("delete")

        client.requestCode(email)
        val session = client.verifyCode(email, sender.codeFor(email)).body<AuthSessionResponse>()
        assertEquals(
            HttpStatusCode.OK,
            client.delete("/v1/me") { bearerAuth(session.accessToken) }.status
        )
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(session.refreshToken).status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/me") { bearerAuth(session.accessToken) }.status
        )
    }

    @Test
    fun `auth logs contain no email code or tokens`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        rootLogger.addAppender(appender)
        val email = uniqueEmail("private")
        var code = ""
        var accessToken = ""
        var refreshToken = ""
        try {
            testApplication {
                val sender = RecordingEmailSender()
                val clock = MutableClock(BASE_TIME)
                application { module(testSettings(), AuthRuntimeOverrides(sender, clock)) }
                val client = jsonClient()
                client.requestCode(email)
                code = sender.codeFor(email)
                val session = client.verifyCode(email, code).body<AuthSessionResponse>()
                accessToken = session.accessToken
                refreshToken = session.refreshToken
            }
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }

        val logs = appender.list.joinToString("\n") { it.formattedMessage }
        assertTrue(code.isNotEmpty() && accessToken.isNotEmpty() && refreshToken.isNotEmpty())
        assertFalse(logs.contains(email))
        assertFalse(logs.contains(code))
        assertFalse(logs.contains(accessToken))
        assertFalse(logs.contains(refreshToken))
    }

    @Test
    fun `malformed auth body returns a structured bad request`() = testApplication {
        val sender = RecordingEmailSender()
        application { module(testSettings(), AuthRuntimeOverrides(sender, MutableClock(BASE_TIME))) }
        val response = jsonClient().post("/v1/auth/code/request") {
            contentType(ContentType.Application.Json)
            setBody("{")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", response.body<ApiErrorEnvelope>().error.code)
    }

    @Test
    fun `smtp failures return a structured retryable auth error`() = testApplication {
        application { module(testSettings(), AuthRuntimeOverrides(FailingEmailSender(), MutableClock(BASE_TIME))) }
        val response = jsonClient().post("/v1/auth/code/request") {
            contentType(ContentType.Application.Json)
            setBody(RequestLoginCodeRequest(uniqueEmail("smtp-failure")))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("code_send_failed", response.body<ApiErrorEnvelope>().error.code)
    }

    private fun testSettings() = ServerSettings.forTest(
        database = databaseSettings,
        authSettings = AuthSettings(maximumIpRequestsPerWindow = 100)
    )

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = false })
        }
    }

    private suspend fun HttpClient.requestCode(email: String) = post("/v1/auth/code/request") {
        contentType(ContentType.Application.Json)
        setBody(RequestLoginCodeRequest(email))
    }

    private suspend fun HttpClient.verifyCode(email: String, code: String) = post("/v1/auth/code/verify") {
        contentType(ContentType.Application.Json)
        setBody(VerifyLoginCodeRequest(email, code, "integration-test"))
    }

    private suspend fun HttpClient.refresh(refreshToken: String) = post("/v1/auth/refresh") {
        contentType(ContentType.Application.Json)
        setBody(RefreshSessionRequest(refreshToken, "integration-test"))
    }

    private fun uniqueEmail(prefix: String) = "$prefix-${UUID.randomUUID()}@example.com"

    private fun differentCode(code: String): String = if (code == "000000") "999999" else "000000"

    private companion object {
        val BASE_TIME: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    }
}

private class RecordingEmailSender : EmailSender {
    private val messages = ConcurrentHashMap<String, LoginCodeEmail>()

    override suspend fun sendLoginCode(email: LoginCodeEmail) {
        messages[email.recipient] = email
    }

    fun codeFor(email: String): String = assertNotNull(messages[email]).code
}

private class FailingEmailSender : EmailSender {
    override suspend fun sendLoginCode(email: LoginCodeEmail) {
        throw MessagingException("simulated smtp failure")
    }
}

private class MutableClock(initial: Instant) : Clock() {
    private val current = AtomicReference(initial)

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current.get()

    fun advance(duration: Duration) {
        current.updateAndGet { it.plus(duration) }
    }
}
