package com.example.catlifepet.memory

import com.example.catlifepet.auth.AccessTokenProvider
import com.example.catlifepet.auth.AuthApiFactory
import com.example.catlifepet.auth.AuthRepository
import com.example.catlifepet.auth.SessionStore
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var source: MemoryRepository
    private lateinit var store: TestSessionStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val tokenProvider = AccessTokenProvider().apply { accessToken = "memory-access" }
        store = TestSessionStore().apply { writeRefreshToken("memory-refresh") }
        val client = AuthApiFactory.createClient(tokenProvider)
        source = MemoryRepository(
            server.url("/").toString(),
            client,
            Gson(),
            AuthRepository(
                AuthApiFactory.create(server.url("/").toString(), tokenProvider, client = client),
                store,
                tokenProvider,
                "memory-test"
            )
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `list create and delete use authenticated memory endpoints`() = runBlocking {
        server.enqueue(json(200, """{"memories":[${memoryJson("one", "nickname", "小雨")}]}"""))
        server.enqueue(json(201, memoryJson("two", "routine", "23:00 睡觉")))
        server.enqueue(MockResponse().setResponseCode(204))

        val listed = source.list()
        val created = source.create("routine", "23:00 睡觉")
        val deleted = source.delete("two")

        assertEquals("小雨", (listed as MemoryOutcome.Success).value.single().content)
        assertEquals("routine", (created as MemoryOutcome.Success).value.kind)
        assertTrue(deleted is MemoryOutcome.Success)
        assertEquals(listOf("/v1/memories", "/v1/memories", "/v1/memories/two"), (1..3).map {
            server.takeRequest().also { request -> assertEquals("Bearer memory-access", request.getHeader("Authorization")) }.path
        })
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun memoryJson(id: String, kind: String, content: String) =
        """{"id":"$id","kind":"$kind","content":"$content","createdAt":"2026-07-22T00:00:00Z","updatedAt":"2026-07-22T00:00:00Z"}"""
}

private class TestSessionStore : SessionStore {
    private var value: String? = null
    override fun readRefreshToken() = value
    override fun writeRefreshToken(refreshToken: String) { value = refreshToken }
    override fun clear() { value = null }
}
