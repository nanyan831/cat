package com.example.catlifepet.chat.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class ChatHttpClient(
    baseUrl: String,
    client: OkHttpClient,
    private val gson: Gson
) {
    private val baseUrl = baseUrl.toHttpUrl()
    private val client = client
    private val streamingClient = client.newBuilder()
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun listConversations(): ConversationListDto = get("v1/conversations")

    suspend fun createConversation(title: String?): ConversationDto = post(
        "v1/conversations",
        CreateConversationRequest(title),
        ConversationDto::class.java
    )

    suspend fun listMessages(conversationId: String): MessageListDto =
        get("v1/conversations/$conversationId/messages")

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url("v1/conversations/$conversationId")).delete().build()).use { response ->
            if (!response.isSuccessful) throw response.toException()
        }
    }

    fun streamMessage(conversationId: String, content: String, clientMessageId: String): Flow<TransportStreamEvent> =
        callbackFlow {
            val body = gson.toJson(SendMessageRequest(content, clientMessageId))
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url("v1/conversations/$conversationId/messages/stream"))
                .post(body)
                .header("Accept", "text/event-stream")
                .build()
            val call = streamingClient.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!call.isCanceled()) {
                        logWarning("Chat stream network failure: ${error.message}")
                        trySend(TransportStreamEvent.Failure(null, "network_error", "无法连接服务器，请稍后重试。", true))
                    }
                    close()
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!response.isSuccessful) {
                                val error = response.toException()
                                logWarning(
                                    "Chat stream rejected: status=${error.status}, code=${error.code}, message=${error.message}"
                                )
                                trySend(TransportStreamEvent.Failure(error.status, error.code, error.message, error.retryable))
                                close()
                                return
                            }
                            val source = response.body?.source()
                            if (source == null) {
                                trySend(TransportStreamEvent.Failure(null, "empty_response", "服务器没有返回内容。", true))
                                close()
                                return
                            }
                            var eventName: String? = null
                            var terminalEvent = false
                            while (!source.exhausted() && !call.isCanceled()) {
                                val line = source.readUtf8Line() ?: break
                                when {
                                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                                    line.startsWith("data:") -> {
                                        parseEvent(eventName, line.removePrefix("data:").trim())?.let { event ->
                                            terminalEvent = event is TransportStreamEvent.Completed ||
                                                event is TransportStreamEvent.Failure
                                            trySend(event)
                                        }
                                    }
                                    line.isBlank() -> eventName = null
                                }
                            }
                            if (!call.isCanceled() && !terminalEvent) {
                                trySend(TransportStreamEvent.Failure(
                                    null,
                                    "stream_interrupted",
                                    "网络中断，消息已保留。",
                                    true
                                ))
                            }
                        }
                    } catch (_: IOException) {
                        if (!call.isCanceled()) {
                            trySend(TransportStreamEvent.Failure(
                                null,
                                "network_error",
                                "网络中断，消息已保留。",
                                true
                            ))
                        }
                    }
                    close()
                }
            })
            awaitClose { call.cancel() }
        }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url(path)).get().build()).use { response ->
            if (!response.isSuccessful) throw response.toException()
            parseBody(response, T::class.java)
        }
    }

    private suspend fun <T> post(path: String, body: Any, type: Class<T>): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(path))
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw response.toException()
            parseBody(response, type)
        }
    }

    private fun execute(request: Request): Response = client.newCall(request).execute()

    private fun <T> parseBody(response: Response, type: Class<T>): T {
        return response.body?.charStream()?.use { gson.fromJson(it, type) }
            ?: throw ChatHttpException(response.code, "empty_response", "服务器没有返回内容。", true)
    }

    private fun parseEvent(name: String?, data: String): TransportStreamEvent? {
        val payload = runCatching { gson.fromJson(data, ChatStreamPayloadDto::class.java) }.getOrNull()
            ?: return TransportStreamEvent.Failure(null, "invalid_stream", "回复数据无法解析。", true)
        return when (name) {
            "delta" -> TransportStreamEvent.Delta(payload.messageId.orEmpty(), payload.delta.orEmpty())
            "completed" -> payload.message?.let(TransportStreamEvent::Completed)
                ?: TransportStreamEvent.Failure(null, "invalid_stream", "完整回复缺少消息。", true)
            "error" -> TransportStreamEvent.Failure(
                null,
                payload.code ?: "ai_stream_failed",
                payload.error ?: "回复中断了，请稍后重试。",
                payload.retryable ?: true
            )
            else -> null
        }
    }

    private fun Response.toException(): ChatHttpException {
        val envelope = runCatching {
            body?.charStream()?.use { gson.fromJson(it, ApiErrorEnvelopeDto::class.java) }
        }.getOrNull()
        val errorCode = envelope?.error?.code ?: "http_error"
        val errorMessage = envelope?.error?.message
            ?: "请求暂时没有完成，请稍后再试。"
        return ChatHttpException(
            code,
            errorCode,
            errorMessage,
            code >= 500
        )
    }

    private fun url(path: String) = baseUrl.newBuilder().addPathSegments(path).build()

    private fun logWarning(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private companion object {
        const val TAG = "CatLifePet"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
