package com.example.catlifepet.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.catlifepet.data.SettingsRepository
import com.example.catlifepet.floating.CatFloatingService
import com.example.catlifepet.permission.OverlayPermissionHelper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE)?.let { ReminderType.valueOfOrNull(it) }
            ?: return Result.failure()
        Log.d(TAG, "WorkManager 任务触发: type=$type, time=${LocalDateTime.now()}")

        val settings = SettingsRepository(applicationContext).getSettings()
        val enabled = when (type) {
            ReminderType.WATER -> settings.waterReminderEnabled
            ReminderType.FOOD -> settings.foodReminderEnabled
            ReminderType.REST -> settings.restReminderEnabled
            ReminderType.SLEEP -> settings.sleepReminderEnabled
        }

        if (enabled) {
            // SLEEP is intentionally allowed during "mute today" so the bedtime companion cue can remain available.
            if (type != ReminderType.SLEEP &&
                settings.muteTodayEnabled &&
                settings.muteTodayDate == LocalDate.now().toString()
            ) {
                Log.d(TAG, "mute today enabled, skip reminder: $type")
                ReminderManager(applicationContext).rescheduleAfterTrigger(type)
                return Result.success()
            }

            val inDoNotDisturb = isInDoNotDisturbNow(
                settings.doNotDisturbEnabled,
                settings.doNotDisturbStart,
                settings.doNotDisturbEnd
            )
            Log.d(TAG, "勿扰判断: enabled=${settings.doNotDisturbEnabled}, inRange=$inDoNotDisturb, type=$type")

            if (inDoNotDisturb && type != ReminderType.SLEEP) {
                Log.d(TAG, "当前处于勿扰时间，跳过提醒: $type")
            } else if (OverlayPermissionHelper.canDrawOverlays(applicationContext)) {
                CatFloatingService.showReminder(applicationContext, type)
            }
        }

        ReminderManager(applicationContext).rescheduleAfterTrigger(type)
        return Result.success()
    }

    private fun isInDoNotDisturbNow(enabled: Boolean, startText: String, endText: String): Boolean {
        if (!enabled) return false
        return runCatching {
            val formatter = DateTimeFormatter.ofPattern("H:mm")
            val start = LocalTime.parse(startText, formatter)
            val end = LocalTime.parse(endText, formatter)
            val now = LocalTime.now()
            if (start <= end) {
                !now.isBefore(start) && now.isBefore(end)
            } else {
                !now.isBefore(start) || now.isBefore(end)
            }
        }.getOrElse {
            Log.w(TAG, "勿扰时间解析失败，使用不跳过策略: start=$startText, end=$endText", it)
            false
        }
    }

    companion object {
        private const val TAG = "CatLifePet"
        const val KEY_TYPE = "reminder_type"
    }
}
