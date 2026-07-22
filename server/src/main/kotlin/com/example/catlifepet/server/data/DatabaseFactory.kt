package com.example.catlifepet.server.data

import com.example.catlifepet.server.config.DatabaseSettings
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection

class DatabaseFactory private constructor(
    private val dataSource: HikariDataSource
) : AutoCloseable {
    fun migrate(): Int {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .load()
            .migrate()
            .migrationsExecuted
    }

    fun <T> transaction(block: (Connection) -> T): T {
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    override fun close() {
        dataSource.close()
    }

    companion object {
        fun open(settings: DatabaseSettings, maximumPoolSize: Int = 8): DatabaseFactory {
            val config = HikariConfig().apply {
                poolName = "CatLifePetDatabase"
                jdbcUrl = settings.jdbcUrl
                username = settings.user
                password = settings.password
                driverClassName = "org.postgresql.Driver"
                this.maximumPoolSize = maximumPoolSize
                minimumIdle = 0
                connectionTimeout = 5_000
                validationTimeout = 3_000
                leakDetectionThreshold = 30_000
            }
            return DatabaseFactory(HikariDataSource(config))
        }
    }
}
