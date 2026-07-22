package com.example.catlifepet.server.chat

import com.example.catlifepet.server.config.AiSettings
import com.example.catlifepet.server.http.ApiException
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal sealed interface SafetyDecision {
    data object Allow : SafetyDecision
    data class FixedReply(val text: String, val outcome: String) : SafetyDecision
}

internal object CompanionSafetyPolicy {
    private val crisisPatterns = listOf(
        Regex("(想死|不想活|自杀|结束生命|伤害自己)", RegexOption.IGNORE_CASE),
        Regex("(kill myself|suicide|end my life|hurt myself)", RegexOption.IGNORE_CASE)
    )
    private val prohibitedPatterns = listOf(
        Regex("(未成年|儿童|child|minor).{0,12}(色情|性行为|sexual)", RegexOption.IGNORE_CASE)
    )

    fun evaluate(content: String): SafetyDecision = when {
        crisisPatterns.any { it.containsMatchIn(content) } -> SafetyDecision.FixedReply(
            "我很在意你现在的安全。请先离开可能伤害自己的东西，马上联系身边可信任的人；如果你正处于紧急危险，请立即联系当地急救或报警服务。",
            "safety_crisis"
        )
        prohibitedPatterns.any { it.containsMatchIn(content) } -> SafetyDecision.FixedReply(
            "这个内容我不能帮助处理。我们可以换个安全的话题，我会继续陪着你。",
            "safety_blocked"
        )
        else -> SafetyDecision.Allow
    }
}

internal class ChatRequestLimiter(
    private val settings: AiSettings,
    private val clock: Clock = Clock.systemUTC()
) {
    private val userRequests = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val ipRequests = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun check(userId: UUID, remoteIp: String) {
        val now = clock.millis()
        if (!allow(userRequests, userId.toString(), settings.maximumUserRequestsPerMinute, now) ||
            !allow(ipRequests, remoteIp.take(128), settings.maximumIpRequestsPerMinute, now)
        ) {
            throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many chat requests. Try again shortly.")
        }
    }

    private fun allow(
        buckets: ConcurrentHashMap<String, ArrayDeque<Long>>,
        key: String,
        maximum: Int,
        now: Long
    ): Boolean {
        val bucket = buckets.computeIfAbsent(key) { ArrayDeque() }
        synchronized(bucket) {
            while (bucket.firstOrNull()?.let { now - it >= WINDOW_MILLIS } == true) bucket.removeFirst()
            if (bucket.size >= maximum) return false
            bucket.addLast(now)
            return true
        }
    }

    private companion object { const val WINDOW_MILLIS = 60_000L }
}
