package com.example.catlifepet.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.catlifepet.data.SettingsRepository
import com.example.catlifepet.floating.CatFloatingService
import com.example.catlifepet.reminder.ReminderType
import com.example.catlifepet.util.ScreenUtils

class OverlayDebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, DEFAULT_DURATION_MILLIS)
            .coerceIn(0L, MAX_DURATION_MILLIS)
        val settings = SettingsRepository(this)
        val requestedPetSize = intent.getIntExtra(EXTRA_PET_SIZE_DP, 0)
            .takeIf { it in SUPPORTED_PET_SIZES }
        requestedPetSize?.let(settings::savePetSize)
        val petSizeDp = requestedPetSize ?: settings.getSettings().petSizeDp
        when (intent.getStringExtra(EXTRA_EDGE)) {
            EDGE_LEFT -> settings.savePetPosition(-ScreenUtils.dp(this, petSizeDp) / 4, DEFAULT_Y)
            EDGE_RIGHT -> settings.savePetPosition(Int.MAX_VALUE, DEFAULT_Y)
        }
        if (durationMillis > 0L) {
            settings.saveTemporaryHideUntil(System.currentTimeMillis() + durationMillis)
        } else {
            settings.clearTemporaryHide()
        }
        Log.d(TAG, "debug process-death setup: hidden for ${durationMillis}ms")
        val reminderName = intent.getStringExtra(EXTRA_REMINDER_TYPE)
        val reminderType = reminderName?.let { ReminderType.valueOfOrNull(it) }
        stopService(Intent(this, CatFloatingService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            CatFloatingService.start(this)
            if (reminderType == null) {
                finish()
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    CatFloatingService.showReminder(this, reminderType)
                    finish()
                }, REMINDER_DELAY_MILLIS)
            }
        }, SERVICE_RESTART_DELAY_MILLIS)
    }

    companion object {
        private const val TAG = "CatLifePet"
        const val EXTRA_DURATION_MILLIS = "duration_millis"
        const val EXTRA_PET_SIZE_DP = "pet_size_dp"
        const val EXTRA_EDGE = "edge"
        const val EXTRA_REMINDER_TYPE = "reminder_type"
        const val EDGE_LEFT = "left"
        const val EDGE_RIGHT = "right"
        private const val DEFAULT_DURATION_MILLIS = 20_000L
        private const val MAX_DURATION_MILLIS = 60_000L
        private const val DEFAULT_Y = 500
        private const val SERVICE_RESTART_DELAY_MILLIS = 250L
        private const val REMINDER_DELAY_MILLIS = 600L
        private val SUPPORTED_PET_SIZES = setOf(80, 120, 160)
    }
}
