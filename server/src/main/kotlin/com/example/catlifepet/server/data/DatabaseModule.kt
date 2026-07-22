package com.example.catlifepet.server.data

import com.example.catlifepet.server.config.ServerSettings
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.util.AttributeKey

data class DatabaseContext(
    val database: DatabaseFactory,
    val repositories: Repositories
)

private val DatabaseContextKey = AttributeKey<DatabaseContext>("CatLifePetDatabaseContext")

val Application.databaseContext: DatabaseContext?
    get() = attributes.getOrNull(DatabaseContextKey)

internal fun Application.configureDatabase(settings: ServerSettings) {
    val databaseSettings = settings.sensitive.database
    if (databaseSettings == null) {
        environment.log.info("Database disabled because no development database is configured")
        return
    }

    val database = DatabaseFactory.open(databaseSettings)
    try {
        val migrationCount = database.migrate()
        environment.log.info("Database ready; appliedMigrations={}", migrationCount)
    } catch (error: Throwable) {
        database.close()
        throw error
    }

    attributes.put(DatabaseContextKey, DatabaseContext(database, Repositories()))
    monitor.subscribe(ApplicationStopped) {
        database.close()
    }
}
