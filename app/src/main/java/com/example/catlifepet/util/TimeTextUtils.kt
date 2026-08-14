package com.example.catlifepet.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeTextUtils {
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val monthDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    private val yearFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")

    fun formatConversationUpdatedAt(
        value: String,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val updatedAt = runCatching { Instant.parse(value) }.getOrNull() ?: return value
        val local = updatedAt.atZone(zone)
        val today = LocalDate.ofInstant(now, zone)
        return when (local.toLocalDate()) {
            today -> "今天 ${local.format(timeFormatter)}"
            today.minusDays(1) -> "昨天 ${local.format(timeFormatter)}"
            else -> if (local.year == today.year) {
                local.format(monthDayFormatter)
            } else {
                local.format(yearFormatter)
            }
        }
    }
}
