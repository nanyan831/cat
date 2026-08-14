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

    @Test
    fun `server english memory errors are converted to friendly local copy`() = runBlocking {
        server.enqueue(json(400, errorJson("invalid_request", "content must not be blank")))
        server.enqueue(json(404, errorJson("not_found", "The memory was not found.")))
        server.enqueue(json(429, errorJson("rate_limited", "Too many requests.")))
        server.enqueue(json(500, errorJson("internal_error", "database detail")))

        assertEquals(
            "记忆内容格式不正确，请检查后再试。",
            (source.create("nickname", "") as MemoryOutcome.Failure).message
        )
        assertEquals(
            "这条记忆已经不存在了，请刷新后再试。",
            (source.delete("missing") as MemoryOutcome.Failure).message
        )
        assertEquals(
            "操作有些频繁，请稍后再试。",
            (source.list() as MemoryOutcome.Failure).message
        )
        assertEquals(
            "服务器暂时不可用，请稍后重试。",
            (source.deleteAll() as MemoryOutcome.Failure).message
        )
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun errorJson(code: String, message: String) =
        """{"error":{"code":"$code","message":"$message"},"requestId":"memory-request"}"""

    private fun memoryJson(id: String, kind: String, content: String) =
        """{"id":"$id","kind":"$kind","content":"$content","createdAt":"2026-07-22T00:00:00Z","updatedAt":"2026-07-22T00:00:00Z"}"""
}

private class TestSessionStore : SessionStore {
    private var value: String? = null
    override fun readRefreshToken() = value
    override fun writeRefreshToken(refreshToken: String) { value = refreshToken }
    override fun clear() { value = null }
}
