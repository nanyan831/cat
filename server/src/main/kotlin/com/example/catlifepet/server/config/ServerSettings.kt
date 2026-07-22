package com.example.catlifepet.server.config

import io.ktor.server.config.ApplicationConfig
import java.net.URI
import java.time.Duration

data class ServerSettings(
    val environment: AppEnvironment,
    val serviceName: String,
    val version: String,
    val publicBaseUrl: String,
    val auth: AuthSettings,
    val ai: AiSettings,
    val developmentMailboxDir: String,
    val sensitive: SensitiveSettings
) {
    companion object {
        private const val DEFAULT_BASE_URL = "http://localhost:8080"
        private const val DEVELOPMENT_JWT_SECRET = "development-only-jwt-secret-change-me"
        private const val DEVELOPMENT_TOKEN_PEPPER = "development-only-token-pepper-change-me"

        fun load(config: ApplicationConfig): ServerSettings {
            val environment = AppEnvironment.parse(
                config.optionalString("catlifepet.environment") ?: AppEnvironment.DEVELOPMENT.wireName
            )
            val configuredBaseUrl = config.optionalString("catlifepet.publicBaseUrl")
            val database = loadDatabaseSettings(config)
            val configuredSensitive = SensitiveSettings(
                database = database,
                jwtSecret = config.optionalString("catlifepet.jwtSecret"),
                tokenPepper = config.optionalString("catlifepet.tokenPepper"),
                smtp = loadSmtpSettings(config),
                openAiApiKey = config.optionalString("catlifepet.openAiApiKey")
            )
            validateConfiguredSecrets(configuredSensitive)

            if (environment == AppEnvironment.PRODUCTION) {
                validateProduction(configuredBaseUrl, configuredSensitive)
            }

            val sensitive = configuredSensitive.withDevelopmentDefaults(environment)
            val ai = loadAiSettings(config, environment, sensitive.openAiApiKey)

            return ServerSettings(
                environment = environment,
                serviceName = "catlifepet-server",
                version = "0.12.0-SNAPSHOT",
                publicBaseUrl = configuredBaseUrl ?: DEFAULT_BASE_URL,
                auth = AuthSettings(
                    issuer = config.optionalString("catlifepet.jwtIssuer") ?: "catlifepet-server",
                    audience = config.optionalString("catlifepet.jwtAudience") ?: "catlifepet-android"
                ),
                ai = ai,
                developmentMailboxDir = config.optionalString("catlifepet.developmentMailboxDir")
                    ?: "server/build/dev-mailbox",
                sensitive = sensitive
            )
        }

        internal fun forTest(
            database: DatabaseSettings? = null,
            authSettings: AuthSettings = AuthSettings(),
            aiSettings: AiSettings = AiSettings()
        ): ServerSettings {
            return ServerSettings(
                environment = AppEnvironment.TEST,
                serviceName = "catlifepet-server",
                version = "test",
                publicBaseUrl = "http://localhost",
                auth = authSettings,
                ai = aiSettings,
                developmentMailboxDir = "build/test-dev-mailbox",
                sensitive = SensitiveSettings(
                    database = database,
                    jwtSecret = DEVELOPMENT_JWT_SECRET,
                    tokenPepper = DEVELOPMENT_TOKEN_PEPPER,
                    smtp = null,
                    openAiApiKey = null
                )
            )
        }

        private fun loadDatabaseSettings(config: ApplicationConfig): DatabaseSettings? {
            val url = config.optionalString("catlifepet.databaseUrl")
            val user = config.optionalString("catlifepet.databaseUser")
            val password = config.optionalString("catlifepet.databasePassword")
            if (url == null && user == null && password == null) return null

            val missing = buildList {
                if (url == null) add("DATABASE_URL")
                if (user == null) add("DATABASE_USER")
                if (password == null) add("DATABASE_PASSWORD")
            }
            if (missing.isNotEmpty()) {
                throw ServerConfigurationException(
                    "Incomplete database configuration: ${missing.joinToString()}"
                )
            }
            if (!url!!.startsWith("jdbc:postgresql://")) {
                throw ServerConfigurationException("DATABASE_URL must be a PostgreSQL JDBC URL.")
            }
            val authority = url.removePrefix("jdbc:postgresql://").substringBefore('/')
            if ('@' in authority) {
                throw ServerConfigurationException(
                    "DATABASE_URL must not contain credentials; use DATABASE_USER and DATABASE_PASSWORD."
                )
            }
            return DatabaseSettings(url, user!!, password!!)
        }

        private fun loadSmtpSettings(config: ApplicationConfig): SmtpSettings? {
            val host = config.optionalString("catlifepet.smtpHost")
            val username = config.optionalString("catlifepet.smtpUsername")
            val password = config.optionalString("catlifepet.smtpPassword")
            val from = config.optionalString("catlifepet.smtpFrom")
            val configuredPort = config.optionalString("catlifepet.smtpPort")
            val configuredStartTls = config.optionalString("catlifepet.smtpStartTls")
            if (
                host == null && username == null && password == null && from == null &&
                configuredPort == null && configuredStartTls == null
            ) {
                return null
            }

            val missing = buildList {
                if (host == null) add("SMTP_HOST")
                if (username == null) add("SMTP_USERNAME")
                if (password == null) add("SMTP_PASSWORD")
                if (from == null) add("SMTP_FROM")
            }
            if (missing.isNotEmpty()) {
                throw ServerConfigurationException("Incomplete SMTP configuration: ${missing.joinToString()}")
            }
            val port = configuredPort?.toIntOrNull() ?: 587
            if (port !in 1..65535) throw ServerConfigurationException("SMTP_PORT must be between 1 and 65535.")
            if (!from!!.contains('@')) throw ServerConfigurationException("SMTP_FROM must be an email address.")
            val startTls = when (configuredStartTls) {
                null -> true
                else -> configuredStartTls.toBooleanStrictOrNull()
                    ?: throw ServerConfigurationException("SMTP_STARTTLS must be true or false.")
            }
            return SmtpSettings(host!!, port, username!!, password!!, from, startTls)
        }

        private fun loadAiSettings(
            config: ApplicationConfig,
            environment: AppEnvironment,
            apiKey: String?
        ): AiSettings {
            val configuredBackend = config.optionalString("catlifepet.aiProvider")
            val backend = configuredBackend?.let(AiBackend::parse)
                ?: if (apiKey == null) AiBackend.FAKE else AiBackend.OPENAI
            if (backend == AiBackend.OPENAI && apiKey == null) {
                throw ServerConfigurationException("OPENAI_API_KEY is required when CATLIFEPET_AI_PROVIDER=openai.")
            }
            if (environment == AppEnvironment.PRODUCTION && backend != AiBackend.OPENAI) {
                throw ServerConfigurationException("Production requires CATLIFEPET_AI_PROVIDER=openai.")
            }

            val baseUrl = config.optionalString("catlifepet.openAiBaseUrl") ?: AiSettings.DEFAULT_OPENAI_BASE_URL
            val uri = runCatching { URI(baseUrl) }.getOrNull()
            val validScheme = uri?.scheme == "https" || environment != AppEnvironment.PRODUCTION && uri?.scheme == "http"
            if (!validScheme || uri?.host.isNullOrBlank()) {
                throw ServerConfigurationException("OPENAI_BASE_URL must be an absolute URL; production requires HTTPS.")
            }

            return AiSettings(
                backend = backend,
                model = config.optionalString("catlifepet.openAiModel") ?: AiSettings.DEFAULT_MODEL,
                openAiBaseUrl = baseUrl.trimEnd('/'),
                storeResponses = config.optionalBoolean("catlifepet.openAiStoreResponses") ?: false,
                requestTimeout = Duration.ofSeconds(
                    (config.optionalInt("catlifepet.aiTimeoutSeconds", 1..120) ?: 30).toLong()
                ),
                maximumInputCharacters = config.optionalInt(
                    "catlifepet.aiMaxInputCharacters",
                    256..50_000
                ) ?: 12_000,
                maximumOutputTokens = config.optionalInt(
                    "catlifepet.aiMaxOutputTokens",
                    32..4_096
                ) ?: 500
            )
        }

        private fun validateConfiguredSecrets(sensitive: SensitiveSettings) {
            if (sensitive.jwtSecret != null && sensitive.jwtSecret.length < 32) {
                throw ServerConfigurationException("CATLIFEPET_JWT_SECRET must contain at least 32 characters.")
            }
            if (sensitive.tokenPepper != null && sensitive.tokenPepper.length < 32) {
                throw ServerConfigurationException("CATLIFEPET_TOKEN_PEPPER must contain at least 32 characters.")
            }
        }

        private fun validateProduction(baseUrl: String?, sensitive: SensitiveSettings) {
            val missing = buildList {
                if (baseUrl == null) add("CATLIFEPET_PUBLIC_BASE_URL")
                if (sensitive.database == null) {
                    add("DATABASE_URL")
                    add("DATABASE_USER")
                    add("DATABASE_PASSWORD")
                }
                if (sensitive.jwtSecret == null) add("CATLIFEPET_JWT_SECRET")
                if (sensitive.tokenPepper == null) add("CATLIFEPET_TOKEN_PEPPER")
                if (sensitive.smtp == null) {
                    add("SMTP_HOST")
                    add("SMTP_USERNAME")
                    add("SMTP_PASSWORD")
                    add("SMTP_FROM")
                }
                if (sensitive.openAiApiKey == null) add("OPENAI_API_KEY")
            }
            if (missing.isNotEmpty()) {
                throw ServerConfigurationException(
                    "Missing required production configuration: ${missing.joinToString()}"
                )
            }

            val uri = runCatching { URI(baseUrl) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
                throw ServerConfigurationException(
                    "Invalid CATLIFEPET_PUBLIC_BASE_URL. Production requires an absolute HTTPS URL."
                )
            }
            if (sensitive.smtp?.startTls == false) {
                throw ServerConfigurationException("Production SMTP requires STARTTLS.")
            }
        }
    }
}

data class AuthSettings(
    val issuer: String = "catlifepet-server",
    val audience: String = "catlifepet-android",
    val accessTokenLifetime: Duration = Duration.ofMinutes(15),
    val refreshTokenLifetime: Duration = Duration.ofDays(30),
    val verificationCodeLifetime: Duration = Duration.ofMinutes(10),
    val maximumCodeAttempts: Int = 5,
    val resendWindow: Duration = Duration.ofMinutes(10),
    val maximumEmailRequestsPerWindow: Int = 3,
    val maximumIpRequestsPerWindow: Int = 10
)

enum class AiBackend(val wireName: String) {
    FAKE("fake"),
    OPENAI("openai");

    companion object {
        fun parse(value: String): AiBackend {
            return entries.firstOrNull { it.wireName == value.trim().lowercase() }
                ?: throw ServerConfigurationException("Invalid CATLIFEPET_AI_PROVIDER. Expected fake or openai.")
        }
    }
}

data class AiSettings(
    val backend: AiBackend = AiBackend.FAKE,
    val model: String = DEFAULT_MODEL,
    val openAiBaseUrl: String = DEFAULT_OPENAI_BASE_URL,
    val storeResponses: Boolean = false,
    val requestTimeout: Duration = Duration.ofSeconds(30),
    val maximumInputCharacters: Int = 12_000,
    val maximumOutputTokens: Int = 500
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-5.6-luna"
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
    }
}

class SensitiveSettings(
    val database: DatabaseSettings?,
    val jwtSecret: String?,
    val tokenPepper: String?,
    val smtp: SmtpSettings?,
    val openAiApiKey: String?
) {
    override fun toString(): String {
        return "SensitiveSettings(database=<redacted>, jwtSecret=<redacted>, tokenPepper=<redacted>, smtp=<redacted>, openAiApiKey=<redacted>)"
    }

    internal fun withDevelopmentDefaults(environment: AppEnvironment): SensitiveSettings {
        if (environment == AppEnvironment.PRODUCTION) return this
        return SensitiveSettings(
            database = database,
            jwtSecret = jwtSecret ?: "development-only-jwt-secret-change-me",
            tokenPepper = tokenPepper ?: "development-only-token-pepper-change-me",
            smtp = smtp,
            openAiApiKey = openAiApiKey
        )
    }
}

class DatabaseSettings internal constructor(
    val jdbcUrl: String,
    val user: String,
    val password: String
) {
    override fun toString(): String = "DatabaseSettings(<redacted>)"
}

class SmtpSettings internal constructor(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val fromAddress: String,
    val startTls: Boolean
) {
    override fun toString(): String = "SmtpSettings(<redacted>)"
}

private fun ApplicationConfig.optionalString(path: String): String? {
    return propertyOrNull(path)?.getString()?.trim()?.takeIf(String::isNotEmpty)
}

private fun ApplicationConfig.optionalBoolean(path: String): Boolean? {
    val value = optionalString(path) ?: return null
    return value.toBooleanStrictOrNull()
        ?: throw ServerConfigurationException("${path.substringAfterLast('.')} must be true or false.")
}

private fun ApplicationConfig.optionalInt(path: String, range: IntRange): Int? {
    val value = optionalString(path) ?: return null
    val parsed = value.toIntOrNull()
        ?: throw ServerConfigurationException("${path.substringAfterLast('.')} must be an integer.")
    if (parsed !in range) {
        throw ServerConfigurationException(
            "${path.substringAfterLast('.')} must be between ${range.first} and ${range.last}."
        )
    }
    return parsed
}
