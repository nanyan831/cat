package com.example.catlifepet.server

import com.example.catlifepet.server.config.AppEnvironment
import com.example.catlifepet.server.config.SensitiveSettings
import com.example.catlifepet.server.config.ServerConfigurationException
import com.example.catlifepet.server.config.ServerSettings
import com.example.catlifepet.server.http.ApiErrorEnvelope
import com.example.catlifepet.server.http.HealthResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerApplicationTest {
    @Test
    fun `health returns service state and a request id`() = testApplication {
        configureTestApplication()

        val response = jsonClient().get("/health")
        val body = response.body<HealthResponse>()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", body.status)
        assertEquals("catlifepet-server", body.service)
        assertEquals("test", body.environment)
        assertEquals(body.requestId, response.headers["X-Request-ID"])
        assertTrue(body.requestId.length >= 8)
    }

    @Test
    fun `valid caller request id is echoed`() = testApplication {
        configureTestApplication()

        val response = jsonClient().get("/health") {
            headers.append("X-Request-ID", "client-request-123")
        }

        assertEquals("client-request-123", response.headers["X-Request-ID"])
        assertEquals("client-request-123", response.body<HealthResponse>().requestId)
    }

    @Test
    fun `invalid caller request id is replaced`() = testApplication {
        configureTestApplication()

        val response = jsonClient().get("/health") {
            headers.append("X-Request-ID", "bad id with spaces")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNotEquals("bad id with spaces", response.headers["X-Request-ID"])
    }

    @Test
    fun `unknown route returns structured error`() = testApplication {
        configureTestApplication()

        val response = jsonClient().get("/missing")
        val body = response.body<ApiErrorEnvelope>()

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found", body.error.code)
        assertEquals(response.headers["X-Request-ID"], body.requestId)
    }

    @Test
    fun `unexpected failure returns a generic structured error`() = testApplication {
        configureTestApplication()

        val response = jsonClient().get("/__test/failure")
        val body = response.body<ApiErrorEnvelope>()

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("internal_error", body.error.code)
        assertFalse(body.error.message.contains("sensitive failure detail"))
        assertEquals(response.headers["X-Request-ID"], body.requestId)
    }

    private fun ApplicationTestBuilder.configureTestApplication() {
        application {
            module(ServerSettings.forTest())
            routing {
                get("/__test/failure") {
                    error("sensitive failure detail")
                }
            }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json() }
    }
}

class ServerSettingsTest {
    @Test
    fun `development uses safe local defaults`() {
        val settings = ServerSettings.load(MapApplicationConfig())

        assertEquals(AppEnvironment.DEVELOPMENT, settings.environment)
        assertEquals("http://localhost:8080", settings.publicBaseUrl)
    }

    @Test
    fun `production missing configuration fails without secret values`() {
        val secret = "must-not-appear-in-errors"
        val config = MapApplicationConfig(
            "catlifepet.environment" to "production",
            "catlifepet.jwtSecret" to secret
        )

        val error = kotlin.runCatching { ServerSettings.load(config) }.exceptionOrNull()

        assertTrue(error is ServerConfigurationException)
        assertTrue(error.message.orEmpty().contains("DATABASE_URL"))
        assertTrue(error.message.orEmpty().contains("OPENAI_API_KEY"))
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test
    fun `production requires https public url`() {
        val config = completeProductionConfig().apply {
            put("catlifepet.publicBaseUrl", "http://api.example.com")
        }

        val error = kotlin.runCatching { ServerSettings.load(config) }.exceptionOrNull()

        assertTrue(error is ServerConfigurationException)
        assertTrue(error.message.orEmpty().contains("HTTPS"))
    }

    @Test
    fun `complete production configuration loads and stays redacted`() {
        val settings = ServerSettings.load(completeProductionConfig())
        val rendered = settings.sensitive.toString()

        assertEquals(AppEnvironment.PRODUCTION, settings.environment)
        assertEquals("https://api.example.com", settings.publicBaseUrl)
        assertFalse(rendered.contains("postgres-password"))
        assertFalse(rendered.contains("jwt-secret-value"))
        assertFalse(rendered.contains("openai-secret-value"))
        assertEquals(
            "SensitiveSettings(databaseUrl=<redacted>, jwtSecret=<redacted>, openAiApiKey=<redacted>)",
            rendered
        )
    }

    private fun completeProductionConfig() = MapApplicationConfig(
        "catlifepet.environment" to "production",
        "catlifepet.publicBaseUrl" to "https://api.example.com",
        "catlifepet.databaseUrl" to "postgresql://user:postgres-password@db/catlifepet",
        "catlifepet.jwtSecret" to "jwt-secret-value",
        "catlifepet.openAiApiKey" to "openai-secret-value"
    )
}
