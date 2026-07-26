package com.example.catlifepet.server.chat

import com.example.catlifepet.server.config.AiSettings
import com.example.catlifepet.server.http.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SafetyControlsTest {
    @Test
    fun `crisis and prohibited content receive local fixed replies`() {
        val crisis = assertIs<SafetyDecision.FixedReply>(CompanionSafetyPolicy.evaluate("我不想活了"))
        val blocked = assertIs<SafetyDecision.FixedReply>(CompanionSafetyPolicy.evaluate("未成年色情内容"))
        assertEquals("safety_crisis", crisis.outcome)
        assertEquals("safety_blocked", blocked.outcome)
        assertIs<SafetyDecision.Allow>(CompanionSafetyPolicy.evaluate("今天想早点睡"))
    }

    @Test
    fun `concurrent limiter admits only configured user burst`() = runTest {
        val limiter = ChatRequestLimiter(
            AiSettings(maximumUserRequestsPerMinute = 10, maximumIpRequestsPerMinute = 100),
            FixedClock(Instant.parse("2026-07-22T00:00:00Z"))
        )
        val user = UUID.randomUUID()
        val admitted = (1..64).map {
            async(Dispatchers.Default) {
                runCatching { limiter.check(user, "127.0.0.1") }.isSuccess
            }
        }.awaitAll()

        assertEquals(10, admitted.count { it })
        assertTrue(admitted.count { !it } == 54)
    }

    @Test
    fun `limiter enforces ip quota and resets after the minute window`() {
        val clock = MutableClock(Instant.parse("2026-07-22T00:00:00Z"))
        val limiter = ChatRequestLimiter(
            AiSettings(maximumUserRequestsPerMinute = 100, maximumIpRequestsPerMinute = 2),
            clock
        )

        limiter.check(UUID.randomUUID(), "203.0.113.9")
        limiter.check(UUID.randomUUID(), "203.0.113.9")
        assertFailsWith<ApiException> {
            limiter.check(UUID.randomUUID(), "203.0.113.9")
        }

        clock.instant = clock.instant.plusSeconds(60)
        limiter.check(UUID.randomUUID(), "203.0.113.9")
    }
}

private class FixedClock(private val instant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = instant
}

private class MutableClock(var instant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = instant
}
