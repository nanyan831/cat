package com.example.catlifepet.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.catlifepet.data.SettingsRepository
import com.example.catlifepet.floating.CatFloatingService
import com.example.catlifepet.permission.OverlayPermissionHelper
import java.time.LocalDateTime
import java.time.ZonedDateTime

class ReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE)?.let { ReminderType.valueOfOrNull(it) }
            ?: return Result.failure()
        Log.d(TAG, "WorkManager 任务触发: type=$type, time=${LocalDateTime.now()}")

        val repository = SettingsRepository(applicationContext)
        var settings = repository.getSettings()
        val now = ZonedDateTime.now()
        if (ReminderSuppressionPolicy.isMuteTodayStale(settings, now)) {
            repository.setMuteToday(false, "")
            settings = repository.getSettings()
            Log.d(TAG, "mute today expired and was cleared")
        }
        val enabled = when (type) {
            ReminderType.WATER -> settings.waterReminderEnabled
            ReminderType.FOOD -> settings.foodReminderEnabled
            ReminderType.REST -> settings.restReminderEnabled
            ReminderType.SLEEP -> settings.sleepReminderEnabled
        }

        if (enabled) {
            val skipped = ReminderSuppressionPolicy.shouldSkip(type, settings, now)
            Log.d(TAG, "提醒静默判断: skipped=$skipped, type=$type, zone=${now.zone}")
            if (skipped) {
                Log.d(TAG, "当前处于勿扰或今日静默，跳过提醒: $type")
            } else if (OverlayPermissionHelper.canDrawOverlays(applicationContext)) {
                CatFloatingService.showReminder(applicationContext, type)
            }
        }

        ReminderManager(applicationContext).rescheduleAfterTrigger(type)
        return Result.success()
    }

    companion object {
        private const val TAG = "CatLifePet"
        const val KEY_TYPE = "reminder_type"
    }
}
