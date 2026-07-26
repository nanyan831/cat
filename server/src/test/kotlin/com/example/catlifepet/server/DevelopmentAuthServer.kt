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
 * Set DEEPSEEK_API_KEY or OPENAI_API_KEY to use a real provider during local device AI tests.
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
    val settings = baseSettings.withOptionalAiProvider()
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

private fun ServerSettings.withOptionalAiProvider(): ServerSettings {
    val deepSeekKey = System.getenv("DEEPSEEK_API_KEY")?.trim()?.takeIf(String::isNotEmpty)
    if (deepSeekKey != null) {
        val model = System.getenv("DEEPSEEK_MODEL")?.trim()?.takeIf(String::isNotEmpty)
            ?: AiSettings.DEFAULT_DEEPSEEK_MODEL
        val baseUrl = System.getenv("DEEPSEEK_BASE_URL")?.trim()?.takeIf(String::isNotEmpty)
            ?: AiSettings.DEFAULT_DEEPSEEK_BASE_URL
        return copy(
            ai = ai.copy(
                backend = AiBackend.DEEPSEEK,
                model = model,
                deepSeekBaseUrl = baseUrl.trimEnd('/'),
                requestTimeout = Duration.ofSeconds(timeoutSeconds()),
                maximumOutputTokens = maxOutputTokens()
            ),
            sensitive = SensitiveSettings(
                database = sensitive.database,
                jwtSecret = sensitive.jwtSecret,
                tokenPepper = sensitive.tokenPepper,
                smtp = sensitive.smtp,
                openAiApiKey = sensitive.openAiApiKey,
                deepSeekApiKey = deepSeekKey
            )
        )
    }

    val apiKey = System.getenv("OPENAI_API_KEY")?.trim()?.takeIf(String::isNotEmpty)
        ?: return this
    val model = System.getenv("OPENAI_MODEL")?.trim()?.takeIf(String::isNotEmpty)
        ?: AiSettings.DEFAULT_OPENAI_MODEL
    val baseUrl = System.getenv("OPENAI_BASE_URL")?.trim()?.takeIf(String::isNotEmpty)
        ?: AiSettings.DEFAULT_OPENAI_BASE_URL

    return copy(
        ai = ai.copy(
            backend = AiBackend.OPENAI,
            model = model,
            openAiBaseUrl = baseUrl.trimEnd('/'),
            requestTimeout = Duration.ofSeconds(timeoutSeconds()),
            maximumOutputTokens = maxOutputTokens()
        ),
        sensitive = SensitiveSettings(
            database = sensitive.database,
            jwtSecret = sensitive.jwtSecret,
            tokenPepper = sensitive.tokenPepper,
            smtp = sensitive.smtp,
            openAiApiKey = apiKey,
            deepSeekApiKey = sensitive.deepSeekApiKey
        )
    )
}

private fun ServerSettings.timeoutSeconds(): Long =
    System.getenv("CATLIFEPET_AI_TIMEOUT_SECONDS")?.toLongOrNull()?.coerceIn(1L, 120L)
        ?: ai.requestTimeout.seconds

private fun ServerSettings.maxOutputTokens(): Int =
    System.getenv("CATLIFEPET_AI_MAX_OUTPUT_TOKENS")?.toIntOrNull()?.coerceIn(32, 4096)
        ?: ai.maximumOutputTokens

private class DeviceTestSecureRandom : SecureRandom() {
    override fun nextInt(bound: Int): Int {
        return if (bound == 1_000_000) DEVICE_TEST_CODE.toInt() else super.nextInt(bound)
    }
}

private const val DEVICE_TEST_CODE = "424242"
