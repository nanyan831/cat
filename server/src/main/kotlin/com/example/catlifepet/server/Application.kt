package com.example.catlifepet.server

import com.example.catlifepet.server.config.ServerSettings
import com.example.catlifepet.server.http.configureHttp
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    module(ServerSettings.load(environment.config))
}

internal fun Application.module(settings: ServerSettings) {
    configureHttp(settings)
}
