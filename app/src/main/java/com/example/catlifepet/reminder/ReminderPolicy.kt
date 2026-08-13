package com.example.catlifepet.reminder

import com.example.catlifepet.data.PetSettings
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object ReminderScheduleCalculator {
    fun delayFor(
        type: ReminderType,
        settings: PetSettings,
        now: ZonedDateTime,
        allowDebugReminder: Boolean = true
    ): Duration {
        if (allowDebugReminder && settings.debugReminderEnabled) {
            return when (type) {
                ReminderType.WATER -> Duration.ofMinutes(1)
                ReminderType.REST -> Duration.ofMinutes(2)
                ReminderType.FOOD -> Duration.ofMinutes(3)
                ReminderType.SLEEP -> Duration.ofMinutes(5)
            }
        }
        return when (type) {
            ReminderType.WATER -> Duration.ofHours(2)
            ReminderType.REST -> Duration.ofMinutes(60)
            ReminderType.FOOD -> delayUntilNext(now, listOf(LocalTime.NOON, LocalTime.of(18, 0)))
            ReminderType.SLEEP -> delayUntilNext(now, listOf(LocalTime.of(23, 30)))
        }
    }

    private fun delayUntilNext(now: ZonedDateTime, times: List<LocalTime>): Duration {
        val candidates = times.map { now.toLocalDate().atTime(it).atZone(now.zone) }
        val target = candidates.firstOrNull { it.isAfter(now) }
            ?: now.toLocalDate().plusDays(1).atTime(times.first()).atZone(now.zone)
        return Duration.between(now, target).coerceAtLeast(Duration.ofSeconds(1))
    }
}

object ReminderSuppressionPolicy {
    fun shouldSkip(type: ReminderType, settings: PetSettings, now: ZonedDateTime): Boolean {
        // Bedtime remains available during mute-today and DND as an intentional companion cue.
        if (type == ReminderType.SLEEP) return false
        if (settings.muteTodayEnabled && settings.muteTodayDate == now.toLocalDate().toString()) return true
        if (!settings.doNotDisturbEnabled) return false
        val start = parseTime(settings.doNotDisturbStart) ?: return false
        val end = parseTime(settings.doNotDisturbEnd) ?: return false
        return contains(now.toLocalTime(), start, end)
    }

    fun isMuteTodayStale(settings: PetSettings, now: ZonedDateTime): Boolean =
        settings.muteTodayEnabled && settings.muteTodayDate != now.toLocalDate().toString()

    internal fun contains(now: LocalTime, start: LocalTime, end: LocalTime): Boolean = when {
        start == end -> true
        start < end -> !now.isBefore(start) && now.isBefore(end)
        else -> !now.isBefore(start) || now.isBefore(end)
    }

    private fun parseTime(value: String): LocalTime? = runCatching {
        LocalTime.parse(value, DateTimeFormatter.ofPattern("H:mm"))
    }.getOrNull()
}
