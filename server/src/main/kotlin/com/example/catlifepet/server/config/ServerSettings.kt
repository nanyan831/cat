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
            val sensitive = SensitiveSettings(
                databaseUrl = config.optionalString("catlifepet.databaseUrl"),
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

        private fun validateProduction(baseUrl: String?, sensitive: SensitiveSettings) {
            val missing = buildList {
                if (baseUrl == null) add("CATLIFEPET_PUBLIC_BASE_URL")
                if (sensitive.databaseUrl == null) add("DATABASE_URL")
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
    val databaseUrl: String?,
    val jwtSecret: String?,
    val openAiApiKey: String?
) {
    override fun toString(): String {
        return "SensitiveSettings(databaseUrl=<redacted>, jwtSecret=<redacted>, openAiApiKey=<redacted>)"
    }
}

private fun ApplicationConfig.optionalString(path: String): String? {
    return propertyOrNull(path)?.getString()?.trim()?.takeIf(String::isNotEmpty)
}
