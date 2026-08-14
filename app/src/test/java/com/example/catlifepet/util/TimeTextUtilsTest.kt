package com.example.catlifepet.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimeTextUtilsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-08-14T06:30:00Z")

    @Test
    fun `formats today's conversation time`() {
        assertEquals(
            "今天 14:12",
            TimeTextUtils.formatConversationUpdatedAt("2026-08-14T06:12:00Z", now, zone)
        )
    }

    @Test
    fun `formats yesterday's conversation time`() {
        assertEquals(
            "昨天 23:58",
            TimeTextUtils.formatConversationUpdatedAt("2026-08-13T15:58:00Z", now, zone)
        )
    }

    @Test
    fun `formats older conversation in current year`() {
        assertEquals(
            "7月22日 08:00",
            TimeTextUtils.formatConversationUpdatedAt("2026-07-22T00:00:00Z", now, zone)
        )
    }

    @Test
    fun `formats older conversation in previous year`() {
        assertEquals(
            "2025年12月31日 20:00",
            TimeTextUtils.formatConversationUpdatedAt("2025-12-31T12:00:00Z", now, zone)
        )
    }

    @Test
    fun `keeps invalid timestamp unchanged`() {
        assertEquals(
            "not-a-time",
            TimeTextUtils.formatConversationUpdatedAt("not-a-time", now, zone)
        )
    }
}
