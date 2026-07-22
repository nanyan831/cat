package com.example.catlifepet.server

import com.example.catlifepet.server.config.AuthSettings
import com.example.catlifepet.server.config.DatabaseSettings
import com.example.catlifepet.server.config.ServerSettings
import com.example.catlifepet.server.auth.AuthRuntimeOverrides
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Path
import java.security.SecureRandom

/**
 * Local-only server used for Android device smoke tests. Verification codes are
 * written to an ignored build directory and are never emitted to application logs.
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
    val settings = ServerSettings.forTest(
        database = database,
        authSettings = AuthSettings(maximumIpRequestsPerWindow = 100)
    ).copy(developmentMailboxDir = mailbox.toString())
    val server = embeddedServer(Netty, host = "0.0.0.0", port = 8080) {
        module(settings, AuthRuntimeOverrides(secureRandom = DeviceTestSecureRandom()))
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { server.stop(1_000, 3_000) }
        runCatching { postgres.close() }
    })

    println("CatLifePet device auth server ready at http://127.0.0.1:8080")
    println("Development mailbox: $mailbox")
    try {
        server.start(wait = true)
    } finally {
        runCatching { server.stop(1_000, 3_000) }
        postgres.close()
    }
}

private class DeviceTestSecureRandom : SecureRandom() {
    override fun nextInt(bound: Int): Int {
        return if (bound == 1_000_000) DEVICE_TEST_CODE.toInt() else super.nextInt(bound)
    }
}

private const val DEVICE_TEST_CODE = "424242"
