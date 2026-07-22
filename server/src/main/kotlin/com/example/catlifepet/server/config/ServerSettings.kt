package com.example.catlifepet.server.config

import io.ktor.server.config.ApplicationConfig
import java.net.URI

data class ServerSettings(
    val environment: AppEnvironment,
    val serviceName: String,
    val version: String,
    val publicBaseUrl: String,
    val sensitive: SensitiveSettings
) {
    companion object {
        private const val DEFAULT_BASE_URL = "http://localhost:8080"

        fun load(config: ApplicationConfig): ServerSettings {
            val environment = AppEnvironment.parse(
                config.optionalString("catlifepet.environment") ?: AppEnvironment.DEVELOPMENT.wireName
            )
            val configuredBaseUrl = config.optionalString("catlifepet.publicBaseUrl")
            val database = loadDatabaseSettings(config)
            val sensitive = SensitiveSettings(
                database = database,
                jwtSecret = config.optionalString("catlifepet.jwtSecret"),
                openAiApiKey = config.optionalString("catlifepet.openAiApiKey")
            )

            if (environment == AppEnvironment.PRODUCTION) {
                validateProduction(configuredBaseUrl, sensitive)
            }

            return ServerSettings(
                environment = environment,
                serviceName = "catlifepet-server",
                version = "0.10.0-SNAPSHOT",
                publicBaseUrl = configuredBaseUrl ?: DEFAULT_BASE_URL,
                sensitive = sensitive
            )
        }

        internal fun forTest(): ServerSettings {
            return ServerSettings(
                environment = AppEnvironment.TEST,
                serviceName = "catlifepet-server",
                version = "test",
                publicBaseUrl = "http://localhost",
                sensitive = SensitiveSettings(null, null, null)
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

        private fun validateProduction(baseUrl: String?, sensitive: SensitiveSettings) {
            val missing = buildList {
                if (baseUrl == null) add("CATLIFEPET_PUBLIC_BASE_URL")
                if (sensitive.database == null) {
                    add("DATABASE_URL")
                    add("DATABASE_USER")
                    add("DATABASE_PASSWORD")
                }
                if (sensitive.jwtSecret == null) add("CATLIFEPET_JWT_SECRET")
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
        }
    }
}

class SensitiveSettings(
    val database: DatabaseSettings?,
    val jwtSecret: String?,
    val openAiApiKey: String?
) {
    override fun toString(): String {
        return "SensitiveSettings(database=<redacted>, jwtSecret=<redacted>, openAiApiKey=<redacted>)"
    }
}

class DatabaseSettings internal constructor(
    val jdbcUrl: String,
    val user: String,
    val password: String
) {
    override fun toString(): String = "DatabaseSettings(<redacted>)"
}

private fun ApplicationConfig.optionalString(path: String): String? {
    return propertyOrNull(path)?.getString()?.trim()?.takeIf(String::isNotEmpty)
}
