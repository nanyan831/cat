package com.example.catlifepet.util

import android.app.Activity
import androidx.annotation.ColorInt
import androidx.core.view.WindowCompat

object SystemBarUtils {
    @Suppress("DEPRECATION")
    fun applyLightBars(
        activity: Activity,
        @ColorInt statusBarColor: Int,
        @ColorInt navigationBarColor: Int
    ) {
        activity.window.statusBarColor = statusBarColor
        activity.window.navigationBarColor = navigationBarColor
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
