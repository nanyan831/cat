package com.example.catlifepet.util

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.WindowManager

object ScreenUtils {
    fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun screenWidth(context: Context): Int {
        val windowManager = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            context.resources.displayMetrics.widthPixels
        }
    }

    fun screenHeight(context: Context): Int {
        val windowManager = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            context.resources.displayMetrics.heightPixels
        }
    }
}
