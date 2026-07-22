package com.example.catlifepet.server.config

enum class AppEnvironment(val wireName: String) {
    DEVELOPMENT("development"),
    TEST("test"),
    PRODUCTION("production");

    companion object {
        fun parse(value: String): AppEnvironment {
            return when (value.trim().lowercase()) {
                "dev", "development" -> DEVELOPMENT
                "test", "testing" -> TEST
                "prod", "production" -> PRODUCTION
                else -> throw ServerConfigurationException(
                    "Invalid CATLIFEPET_ENV. Expected development, test, or production."
                )
            }
        }
    }
}
