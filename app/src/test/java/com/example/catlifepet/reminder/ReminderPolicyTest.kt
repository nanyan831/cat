package com.example.catlifepet.reminder

import com.example.catlifepet.data.PetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderPolicyTest {
    @Test
    fun `cross-midnight DND includes start and excludes end`() {
        val settings = PetSettings(
            doNotDisturbEnabled = true,
            doNotDisturbStart = "23:30",
            doNotDisturbEnd = "07:30"
        )
        assertTrue(ReminderSuppressionPolicy.shouldSkip(ReminderType.WATER, settings, at("2026-07-22T23:30:00+08:00")))
        assertTrue(ReminderSuppressionPolicy.shouldSkip(ReminderType.FOOD, settings, at("2026-07-23T07:29:59+08:00")))
        assertFalse(ReminderSuppressionPolicy.shouldSkip(ReminderType.REST, settings, at("2026-07-23T07:30:00+08:00")))
        assertFalse(ReminderSuppressionPolicy.shouldSkip(ReminderType.SLEEP, settings, at("2026-07-23T01:00:00+08:00")))
    }

    @Test
    fun `mute today expires on the next local date`() {
        val settings = PetSettings(muteTodayEnabled = true, muteTodayDate = "2026-07-22")
        assertTrue(ReminderSuppressionPolicy.shouldSkip(ReminderType.WATER, settings, at("2026-07-22T12:00:00+08:00")))
        val tomorrow = at("2026-07-23T00:00:00+08:00")
        assertTrue(ReminderSuppressionPolicy.isMuteTodayStale(settings, tomorrow))
        assertFalse(ReminderSuppressionPolicy.shouldSkip(ReminderType.WATER, settings, tomorrow))
    }

    @Test
    fun `meal and sleep schedules preserve local wall clock across timezone and DST`() {
        val normal = PetSettings()
        assertEquals(Duration.ofHours(1), ReminderScheduleCalculator.delayFor(
            ReminderType.FOOD, normal, at("2026-07-22T11:00:00+08:00")
        ))
        assertEquals(Duration.ofHours(6), ReminderScheduleCalculator.delayFor(
            ReminderType.FOOD, normal, at("2026-07-22T12:00:00+08:00")
        ))

        val berlin = ZonedDateTime.of(2026, 3, 29, 1, 30, 0, 0, ZoneId.of("Europe/Berlin"))
        val foodDelay = ReminderScheduleCalculator.delayFor(ReminderType.FOOD, normal, berlin)
        assertEquals(LocalTime.NOON, berlin.plus(foodDelay).toLocalTime())
        val sleepDelay = ReminderScheduleCalculator.delayFor(ReminderType.SLEEP, normal, berlin)
        assertEquals(LocalTime.of(23, 30), berlin.plus(sleepDelay).toLocalTime())
    }

    @Test
    fun `debug intervals are deterministic`() {
        val debug = PetSettings(debugReminderEnabled = true)
        val now = at("2026-07-22T12:00:00+08:00")
        assertEquals(Duration.ofMinutes(1), ReminderScheduleCalculator.delayFor(ReminderType.WATER, debug, now))
        assertEquals(Duration.ofMinutes(2), ReminderScheduleCalculator.delayFor(ReminderType.REST, debug, now))
        assertEquals(Duration.ofMinutes(3), ReminderScheduleCalculator.delayFor(ReminderType.FOOD, debug, now))
        assertEquals(Duration.ofMinutes(5), ReminderScheduleCalculator.delayFor(ReminderType.SLEEP, debug, now))
    }

    @Test
    fun `release scheduling ignores stale debug reminder setting`() {
        val staleDebug = PetSettings(debugReminderEnabled = true)
        val now = at("2026-07-22T12:00:00+08:00")

        assertEquals(Duration.ofHours(2), ReminderScheduleCalculator.delayFor(
            ReminderType.WATER, staleDebug, now, allowDebugReminder = false
        ))
        assertEquals(Duration.ofMinutes(60), ReminderScheduleCalculator.delayFor(
            ReminderType.REST, staleDebug, now, allowDebugReminder = false
        ))
        assertEquals(Duration.ofHours(6), ReminderScheduleCalculator.delayFor(
            ReminderType.FOOD, staleDebug, now, allowDebugReminder = false
        ))
        assertEquals(Duration.ofHours(11).plusMinutes(30), ReminderScheduleCalculator.delayFor(
            ReminderType.SLEEP, staleDebug, now, allowDebugReminder = false
        ))
    }

    private fun at(value: String) = ZonedDateTime.parse(value)
}
