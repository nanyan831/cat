package com.example.catlifepet.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.example.catlifepet.data.SettingsRepository
import com.example.catlifepet.floating.CatFloatingService

class OverlayDebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, DEFAULT_DURATION_MILLIS)
            .coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)
        SettingsRepository(this).saveTemporaryHideUntil(
            System.currentTimeMillis() + durationMillis
        )
        Log.d(TAG, "debug process-death setup: hidden for ${durationMillis}ms")
        CatFloatingService.start(this)
        finish()
    }

    companion object {
        private const val TAG = "CatLifePet"
        const val EXTRA_DURATION_MILLIS = "duration_millis"
        private const val DEFAULT_DURATION_MILLIS = 20_000L
        private const val MIN_DURATION_MILLIS = 1_000L
        private const val MAX_DURATION_MILLIS = 60_000L
    }
}
