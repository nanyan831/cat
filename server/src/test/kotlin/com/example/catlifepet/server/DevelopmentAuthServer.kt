package com.example.catlifepet.server

import com.example.catlifepet.server.config.AuthSettings
import com.example.catlifepet.server.config.AiBackend
import com.example.catlifepet.server.config.AiSettings
import com.example.catlifepet.server.config.DatabaseSettings
import com.example.catlifepet.server.config.SensitiveSettings
import com.example.catlifepet.server.config.ServerSettings
import com.example.catlifepet.server.auth.AuthRuntimeOverrides
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration

/**
 * Local-only server used for Android device smoke tests. Verification codes are
 * written to an ignored build directory and are never emitted to application logs.
 * Set OPENAI_API_KEY to use the real OpenAI provider during local device AI tests.
 */
fun main() {
    val postgres = EmbeddedPostgres.builder()
        .setRegisterShutdownHook(false)
        .start()
    val database = DatabaseSettings(
        jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
        user = "postgres",
        password = "postgres"
    )
    val mailbox = Path.of("build", "device-test-mailbox").toAbsolutePath()
    val baseSettings = ServerSettings.forTest(
        database = database,
        authSettings = AuthSettings(maximumIpRequestsPerWindow = 100)
    ).copy(developmentMailboxDir = mailbox.toString())
    val settings = baseSettings.withOptionalOpenAiProvider()
    val server = embeddedServer(Netty, host = "0.0.0.0", port = 8080) {
        module(settings, AuthRuntimeOverrides(secureRandom = DeviceTestSecureRandom()))
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { server.stop(1_000, 3_000) }
        runCatching { postgres.close() }
    })

    println("CatLifePet device auth server ready at http://127.0.0.1:8080")
    println("Development mailbox: $mailbox")
    println("AI provider: ${settings.ai.backend.wireName}, model: ${settings.ai.model}")
    try {
        server.start(wait = true)
    } finally {
        runCatching { server.stop(1_000, 3_000) }
        postgres.close()
    }
}

private fun ServerSettings.withOptionalOpenAiProvider(): ServerSettings {
    val apiKey = System.getenv("OPENAI_API_KEY")?.trim()?.takeIf(String::isNotEmpty)
        ?: return this
    val model = System.getenv("OPENAI_MODEL")?.trim()?.takeIf(String::isNotEmpty)
        ?: AiSettings.DEFAULT_MODEL
    val baseUrl = System.getenv("OPENAI_BASE_URL")?.trim()?.takeIf(String::isNotEmpty)
        ?: AiSettings.DEFAULT_OPENAI_BASE_URL
    val timeoutSeconds = System.getenv("CATLIFEPET_AI_TIMEOUT_SECONDS")?.toLongOrNull()?.coerceIn(1L, 120L)
        ?: ai.requestTimeout.seconds
    val maxOutputTokens = System.getenv("CATLIFEPET_AI_MAX_OUTPUT_TOKENS")?.toIntOrNull()?.coerceIn(32, 4096)
        ?: ai.maximumOutputTokens

    return copy(
        ai = ai.copy(
            backend = AiBackend.OPENAI,
            model = model,
            openAiBaseUrl = baseUrl.trimEnd('/'),
            requestTimeout = Duration.ofSeconds(timeoutSeconds),
            maximumOutputTokens = maxOutputTokens
        ),
        sensitive = SensitiveSettings(
            database = sensitive.database,
            jwtSecret = sensitive.jwtSecret,
            tokenPepper = sensitive.tokenPepper,
            smtp = sensitive.smtp,
            openAiApiKey = apiKey
        )
    )
}

private class DeviceTestSecureRandom : SecureRandom() {
    override fun nextInt(bound: Int): Int {
        return if (bound == 1_000_000) DEVICE_TEST_CODE.toInt() else super.nextInt(bound)
    }
}

private const val DEVICE_TEST_CODE = "424242"
