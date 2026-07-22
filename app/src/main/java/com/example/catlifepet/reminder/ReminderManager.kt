package com.example.catlifepet.reminder

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.catlifepet.data.SettingsRepository
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class ReminderManager(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val settingsRepository = SettingsRepository(appContext)

    fun syncAll() {
        val settings = settingsRepository.getSettings()
        Log.d(TAG, "调试模式开关状态: ${settings.debugReminderEnabled}")
        if (settings.waterReminderEnabled) scheduleWaterReminder() else cancelReminder(ReminderType.WATER)
        if (settings.foodReminderEnabled) scheduleFoodReminder() else cancelReminder(ReminderType.FOOD)
        if (settings.restReminderEnabled) scheduleRestReminder() else cancelReminder(ReminderType.REST)
        if (settings.sleepReminderEnabled) scheduleSleepReminder() else cancelReminder(ReminderType.SLEEP)
    }

    fun scheduleWaterReminder() {
        if (settingsRepository.getSettings().debugReminderEnabled) {
            scheduleOneShot(ReminderType.WATER, Duration.ofMinutes(1))
            return
        }
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(2, TimeUnit.HOURS)
            .setInputData(workDataOf(ReminderWorker.KEY_TYPE to ReminderType.WATER.name))
            .build()
        workManager.enqueueUniquePeriodicWork(
            workName(ReminderType.WATER),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleFoodReminder() {
        val delay = if (settingsRepository.getSettings().debugReminderEnabled) {
            Duration.ofMinutes(3)
        } else {
            nextDelayForMeal()
        }
        scheduleOneShot(ReminderType.FOOD, delay)
    }

    fun scheduleRestReminder() {
        if (settingsRepository.getSettings().debugReminderEnabled) {
            scheduleOneShot(ReminderType.REST, Duration.ofMinutes(2))
            return
        }
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(60, TimeUnit.MINUTES)
            .setInputData(workDataOf(ReminderWorker.KEY_TYPE to ReminderType.REST.name))
            .build()
        workManager.enqueueUniquePeriodicWork(
            workName(ReminderType.REST),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleSleepReminder() {
        val delay = if (settingsRepository.getSettings().debugReminderEnabled) {
            Duration.ofMinutes(5)
        } else {
            delayUntil(LocalTime.of(23, 30))
        }
        scheduleOneShot(ReminderType.SLEEP, delay)
    }

    fun cancelReminder(type: ReminderType) {
        workManager.cancelUniqueWork(workName(type))
    }

    internal fun rescheduleAfterTrigger(type: ReminderType) {
        if (settingsRepository.getSettings().debugReminderEnabled) {
            when (type) {
                ReminderType.WATER -> scheduleWaterReminder()
                ReminderType.REST -> scheduleRestReminder()
                ReminderType.FOOD -> scheduleFoodReminder()
                ReminderType.SLEEP -> scheduleSleepReminder()
            }
            return
        }
        when (type) {
            ReminderType.FOOD -> scheduleFoodReminder()
            ReminderType.SLEEP -> scheduleSleepReminder()
            ReminderType.WATER, ReminderType.REST -> Unit
        }
    }

    private fun scheduleOneShot(type: ReminderType, delay: Duration) {
        Log.d(TAG, "安排提醒: type=$type, delayMinutes=${delay.toMinutes()}, debug=${settingsRepository.getSettings().debugReminderEnabled}")
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.toMillis().coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_TYPE to type.name))
            .build()
        workManager.enqueueUniqueWork(
            workName(type),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun nextDelayForMeal(): Duration {
        val now = LocalTime.now()
        return when {
            now.isBefore(LocalTime.NOON) -> delayUntil(LocalTime.NOON)
            now.isBefore(LocalTime.of(18, 0)) -> delayUntil(LocalTime.of(18, 0))
            else -> delayUntil(LocalTime.NOON)
        }
    }

    private fun delayUntil(targetTime: LocalTime): Duration {
        val now = LocalDateTime.now()
        var target = now.withHour(targetTime.hour).withMinute(targetTime.minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return Duration.between(now, target)
    }

    private fun workName(type: ReminderType): String = "cat_life_pet_reminder_${type.name.lowercase()}"

    private companion object {
        const val TAG = "CatLifePet"
    }
}
