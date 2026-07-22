package com.example.catlifepet.server.chat

import com.example.catlifepet.server.auth.AUTH_PROVIDER
import com.example.catlifepet.server.auth.AuthService
import com.example.catlifepet.server.auth.requireIdentity
import com.example.catlifepet.server.auth.toUuidOrNull
import com.example.catlifepet.server.http.ApiException
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun Application.configureChatRoutes(authService: AuthService, chatService: ChatService) {
    val json = Json { explicitNulls = false }
    routing {
        authenticate(AUTH_PROVIDER) {
            route("/v1/conversations") {
                post {
                    val identity = call.requireIdentity(authService)
                    call.respond(HttpStatusCode.Created, chatService.createConversation(identity.user.id, call.receive()))
                }
                get {
                    val identity = call.requireIdentity(authService)
                    call.respond(chatService.listConversations(identity.user.id))
                }
                delete {
                    val identity = call.requireIdentity(authService)
                    chatService.deleteAllConversations(identity.user.id)
                    call.respond(HttpStatusCode.NoContent)
                }
                route("/{conversationId}") {
                    get("/messages") {
                        val identity = call.requireIdentity(authService)
                        call.respond(chatService.listMessages(identity.user.id, call.conversationId()))
                    }
                    post("/messages/stream") {
                        val identity = call.requireIdentity(authService)
                        val turn = chatService.prepareTurn(
                            identity.user.id,
                            call.conversationId(),
                            call.receive()
                        )
                        call.response.header(HttpHeaders.CacheControl, CacheControl.NoCache(null).toString())
                        call.response.header("X-Accel-Buffering", "no")
                        call.respondTextWriter(ContentType.Text.EventStream) {
                            chatService.streamTurn(turn).collect { event ->
                                val payload = when (event) {
                                    is ChatStreamEvent.Delta -> ChatStreamPayload(
                                        messageId = event.messageId,
                                        delta = event.text
                                    )
                                    is ChatStreamEvent.Completed -> ChatStreamPayload(message = event.message)
                                    is ChatStreamEvent.Error -> ChatStreamPayload(
                                        code = event.code,
                                        error = event.message,
                                        retryable = event.retryable
                                    )
                                }
                                write("event: ${event.eventName}\n")
                                write("data: ${json.encodeToString(payload)}\n\n")
                                flush()
                            }
                        }
                    }
                    delete {
                        val identity = call.requireIdentity(authService)
                        chatService.deleteConversation(identity.user.id, call.conversationId())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.conversationId() =
    parameters["conversationId"]?.toUuidOrNull()
        ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "conversationId must be a UUID.")
