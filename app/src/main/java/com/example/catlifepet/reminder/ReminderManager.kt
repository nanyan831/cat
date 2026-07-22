package com.example.catlifepet.reminder

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.catlifepet.data.SettingsRepository
import java.time.Duration
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class ReminderManager(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone()
) {
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
        scheduleOneShot(ReminderType.WATER, nextDelay(ReminderType.WATER))
    }

    fun scheduleFoodReminder() {
        scheduleOneShot(ReminderType.FOOD, nextDelay(ReminderType.FOOD))
    }

    fun scheduleRestReminder() {
        scheduleOneShot(ReminderType.REST, nextDelay(ReminderType.REST))
    }

    fun scheduleSleepReminder() {
        scheduleOneShot(ReminderType.SLEEP, nextDelay(ReminderType.SLEEP))
    }

    fun cancelReminder(type: ReminderType) {
        workManager.cancelUniqueWork(workName(type))
    }

    internal fun rescheduleAfterTrigger(type: ReminderType) {
        val settings = settingsRepository.getSettings()
        val enabled = when (type) {
            ReminderType.WATER -> settings.waterReminderEnabled
            ReminderType.FOOD -> settings.foodReminderEnabled
            ReminderType.REST -> settings.restReminderEnabled
            ReminderType.SLEEP -> settings.sleepReminderEnabled
        }
        if (!enabled) {
            cancelReminder(type)
            Log.d(TAG, "提醒已关闭，不再续排: type=$type")
            return
        }
        when (type) {
            ReminderType.WATER -> scheduleWaterReminder()
            ReminderType.REST -> scheduleRestReminder()
            ReminderType.FOOD -> scheduleFoodReminder()
            ReminderType.SLEEP -> scheduleSleepReminder()
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

    private fun nextDelay(type: ReminderType): Duration = ReminderScheduleCalculator.delayFor(
        type,
        settingsRepository.getSettings(),
        ZonedDateTime.now(clock)
    )

    companion object {
        private const val TAG = "CatLifePet"
        fun workName(type: ReminderType): String = "cat_life_pet_reminder_${type.name.lowercase()}"
    }
}
