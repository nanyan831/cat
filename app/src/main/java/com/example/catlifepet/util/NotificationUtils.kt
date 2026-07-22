package com.example.catlifepet.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.catlifepet.MainActivity
import com.example.catlifepet.R
import com.example.catlifepet.reminder.ReminderType

object NotificationUtils {
    const val CHANNEL_ID = "cat_life_pet_foreground"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CatLifePet 桌宠",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持小猫桌宠在屏幕上陪伴你"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildForegroundNotification(context: Context): Notification {
        ensureChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.cat_idle)
            .setContentTitle("CatLifePet 正在陪伴你")
            .setContentText("点击回到设置页，或关闭小猫桌宠")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showReminderFallback(context: Context, type: ReminderType) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "notification permission unavailable; fallback reminder skipped: $type")
            return
        }
        ensureChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            100 + type.ordinal,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val message = when (type) {
            ReminderType.WATER -> "喝口水吧，我陪你。"
            ReminderType.FOOD -> "到饭点啦，好好吃饭。"
            ReminderType.REST -> "坐太久啦，起来活动一下。"
            ReminderType.SLEEP -> "已经很晚啦，该休息了。"
        }
        context.getSystemService(NotificationManager::class.java).notify(
            2000 + type.ordinal,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.cat_idle)
                .setContentTitle("CatLifePet 生活提醒")
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    fun canPostNotifications(context: Context): Boolean {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private const val TAG = "CatLifePet"
}
