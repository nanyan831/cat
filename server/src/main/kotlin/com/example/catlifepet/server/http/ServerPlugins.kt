package com.example.catlifepet.server.http

import com.example.catlifepet.server.config.ServerSettings
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID

private const val REQUEST_ID_HEADER = "X-Request-ID"
private val VALID_REQUEST_ID = Regex("^[A-Za-z0-9._:-]{8,128}$")

internal fun Application.configureHttp(settings: ServerSettings) {
    val applicationLog = environment.log

    install(ContentNegotiation) {
        json(
            Json {
                explicitNulls = false
                ignoreUnknownKeys = false
            }
        )
    }

    install(CallId) {
        retrieveFromHeader(REQUEST_ID_HEADER)
        verify(VALID_REQUEST_ID::matches)
        generate { UUID.randomUUID().toString() }
        replyToHeader(REQUEST_ID_HEADER)
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        format { call ->
            SanitizedRequestLog.format(
                method = call.request.httpMethod.value,
                rawPath = call.request.path(),
                statusCode = call.response.status()?.value
            )
        }
    }

    install(StatusPages) {
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorEnvelope(ApiError("invalid_request", "The request body is invalid."), call.requestId())
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorEnvelope(ApiError("invalid_request", "The request body is invalid."), call.requestId())
            )
        }
        exception<ApiException> { call, cause ->
            call.respond(
                cause.status,
                ApiErrorEnvelope(ApiError(cause.code, cause.message), call.requestId())
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                ApiErrorEnvelope(ApiError("not_found", "The requested resource was not found."), call.requestId())
            )
        }
        exception<Throwable> { call, cause ->
            applicationLog.error(
                "Unhandled server error: requestId={}, type={}",
                call.requestId(),
                cause::class.simpleName ?: "Unknown"
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorEnvelope(
                    ApiError("internal_error", "The server could not complete the request."),
                    call.requestId()
                )
            )
        }
    }

    configureRoutes(settings)
    applicationLog.info(
        "CatLifePet server ready: service={}, environment={}",
        settings.serviceName,
        settings.environment.wireName
    )
}

internal fun io.ktor.server.application.ApplicationCall.requestId(): String {
    return callId ?: "unavailable"
}
