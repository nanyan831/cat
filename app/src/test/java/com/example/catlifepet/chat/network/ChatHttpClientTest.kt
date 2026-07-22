package com.example.catlifepet.chat.network

import com.google.gson.Gson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ChatHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = ChatHttpClient(server.url("/").toString(), OkHttpClient(), Gson())
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `SSE parser keeps ordered deltas and completed message`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(successStream()))

        val events = client.streamMessage("conversation", "你好", "client").toList()

        assertEquals(listOf("我在", "听。"), events.filterIsInstance<TransportStreamEvent.Delta>().map { it.text })
        assertEquals("我在听。", events.filterIsInstance<TransportStreamEvent.Completed>().single().message.content)
    }

    @Test
    fun `body disconnect produces retryable failure instead of hanging`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: delta\ndata: {\"messageId\":\"assistant\",\"delta\":\"开\"}\n\n" + "x".repeat(10_000))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val events = client.streamMessage("conversation", "保留我", "client").toList()

        val failure = events.filterIsInstance<TransportStreamEvent.Failure>().single()
        assertTrue(failure.retryable)
        assertTrue(failure.code == "network_error" || failure.code == "stream_interrupted")
    }

    private fun successStream() = """
        event: delta
        data: {"messageId":"assistant","delta":"我在"}

        event: delta
        data: {"messageId":"assistant","delta":"听。"}

        event: completed
        data: {"message":{"id":"assistant","sequenceNumber":2,"role":"assistant","content":"我在听。","status":"completed","createdAt":"2026-07-22T00:00:00Z","updatedAt":"2026-07-22T00:00:01Z"}}

    """.trimIndent()
}
