package com.example.catlifepet.auth

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var tokenProvider: AccessTokenProvider
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = MemorySessionStore()
        tokenProvider = AccessTokenProvider()
        repository = AuthRepository(
            api = AuthApiFactory.create(server.url("/").toString(), tokenProvider),
            sessionStore = store,
            tokenProvider = tokenProvider,
            deviceLabel = "unit-test-device"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful verification stores refresh token and keeps access token in memory`() = runBlocking {
        server.enqueue(jsonResponse(200, sessionJson("access-one", "refresh-one")))

        val outcome = repository.verifyCode("cat@example.com", "123456")

        assertTrue(outcome is AuthOutcome.Success)
        assertEquals("refresh-one", store.readRefreshToken())
        assertEquals("access-one", tokenProvider.accessToken)
        val request = server.takeRequest()
        assertEquals("/v1/auth/code/verify", request.path)
        assertNull(request.getHeader("Authorization"))
        assertFalse(request.body.readUtf8().contains("access-one"))
    }

    @Test
    fun `authenticated 401 rotates session once and retries with the new access token`() = runBlocking {
        store.writeRefreshToken("refresh-old")
        tokenProvider.accessToken = "access-stale"
        server.enqueue(jsonResponse(401, errorJson("invalid_access_token")))
        server.enqueue(jsonResponse(200, sessionJson("access-new", "refresh-new")))
        server.enqueue(jsonResponse(200, userJson()))

        val outcome = repository.getMe()

        assertTrue(outcome is AuthOutcome.Success)
        assertEquals("refresh-new", store.readRefreshToken())
        assertEquals("access-new", tokenProvider.accessToken)
        val firstMe = server.takeRequest()
        val refresh = server.takeRequest()
        val secondMe = server.takeRequest()
        assertEquals("Bearer access-stale", firstMe.getHeader("Authorization"))
        assertEquals("/v1/auth/refresh", refresh.path)
        assertNull(refresh.getHeader("Authorization"))
        assertTrue(refresh.body.readUtf8().contains("refresh-old"))
        assertEquals("Bearer access-new", secondMe.getHeader("Authorization"))
    }

    @Test
    fun `network failure keeps refresh credential for a later retry`() = runBlocking {
        store.writeRefreshToken("refresh-offline")
        server.shutdown()

        val outcome = repository.restoreSession()

        assertTrue(outcome is AuthOutcome.Failure)
        assertEquals("network_error", (outcome as AuthOutcome.Failure).code)
        assertTrue(outcome.retryable)
        assertEquals("refresh-offline", store.readRefreshToken())
    }

    @Test
    fun `invalid verification code does not create a local session`() = runBlocking {
        server.enqueue(jsonResponse(401, errorJson("invalid_code")))

        val outcome = repository.verifyCode("cat@example.com", "000000")

        assertTrue(outcome is AuthOutcome.Failure)
        assertEquals("invalid_code", (outcome as AuthOutcome.Failure).code)
        assertEquals("验证码错误或已过期，请重新输入。", outcome.message)
        assertEquals("验证码错误或已过期，请重新输入。", outcome.toUserMessage())
        assertNull(store.readRefreshToken())
        assertNull(tokenProvider.accessToken)
    }

    @Test
    fun `server english error messages are converted to friendly auth copy`() = runBlocking {
        server.enqueue(jsonResponse(429, errorJson("rate_limited", "Too many requests.")))

        val outcome = repository.requestCode("cat@example.com")

        assertTrue(outcome is AuthOutcome.Failure)
        assertEquals("rate_limited", (outcome as AuthOutcome.Failure).code)
        assertEquals("操作有些频繁，请稍后再试。", outcome.message)
        assertFalse(outcome.message.contains("Too many"))
    }

    @Test
    fun `gateway 403 is converted to temporary cloud service copy`() = runBlocking {
        server.enqueue(jsonResponse(403, "<html>blocked</html>"))

        val outcome = repository.requestCode("cat@example.com")

        assertTrue(outcome is AuthOutcome.Failure)
        assertEquals("http_error", (outcome as AuthOutcome.Failure).code)
        assertEquals("云端服务暂时不可用，请稍后重试。", outcome.message)
        assertFalse(outcome.message.contains("权限"))
    }

    @Test
    fun `network auth failure uses friendly retry copy`() = runBlocking {
        server.shutdown()

        val outcome = repository.requestCode("cat@example.com")

        assertTrue(outcome is AuthOutcome.Failure)
        assertEquals("network_error", (outcome as AuthOutcome.Failure).code)
        assertEquals("无法连接服务器，请检查网络后重试。", outcome.message)
        assertTrue(outcome.retryable)
    }

    @Test
    fun `logout calls server and always clears local credentials`() = runBlocking {
        store.writeRefreshToken("refresh-one")
        tokenProvider.accessToken = "access-one"
        server.enqueue(jsonResponse(200, "{\"success\":true}"))

        val outcome = repository.logout()

        assertTrue(outcome is AuthOutcome.Success)
        assertNull(store.readRefreshToken())
        assertNull(tokenProvider.accessToken)
        val request = server.takeRequest()
        assertEquals("/v1/auth/logout", request.path)
        assertEquals("Bearer access-one", request.getHeader("Authorization"))
    }

    private fun jsonResponse(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun sessionJson(accessToken: String, refreshToken: String) = """
        {
          "accessToken":"$accessToken",
          "refreshToken":"$refreshToken",
          "tokenType":"Bearer",
          "expiresInSeconds":900,
          "user":${userJson()}
        }
    """.trimIndent()

    private fun userJson() = """
        {"id":"user-1","email":"cat@example.com","displayName":"Momo","timeZone":"Asia/Shanghai"}
    """.trimIndent()

    private fun errorJson(code: String, message: String = "request rejected") = """
        {"error":{"code":"$code","message":"$message"},"requestId":"request-1"}
    """.trimIndent()
}

private class MemorySessionStore : SessionStore {
    private var refreshToken: String? = null

    override fun readRefreshToken(): String? = refreshToken

    override fun writeRefreshToken(refreshToken: String) {
        this.refreshToken = refreshToken
    }

    override fun clear() {
        refreshToken = null
    }
}
