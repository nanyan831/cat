package com.example.catlifepet.floating

import android.app.Service
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.example.catlifepet.permission.OverlayPermissionHelper
import com.example.catlifepet.data.SettingsRepository
import com.example.catlifepet.reminder.ReminderManager
import com.example.catlifepet.reminder.ReminderType
import com.example.catlifepet.util.NotificationUtils

class CatFloatingService : Service() {
    private var controller: PetWindowController? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "服务启动 onCreate")
        controller = PetWindowController(this)
        ServiceCompat.startForeground(
            this,
            NotificationUtils.NOTIFICATION_ID,
            NotificationUtils.buildForegroundNotification(this),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_START -> {
                if (OverlayPermissionHelper.canDrawOverlays(this)) {
                    controller?.show()
                    ReminderManager(this).syncAll()
                } else {
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                SettingsRepository(this).clearTemporaryHide()
                stopSelf()
            }

            ACTION_REMINDER -> {
                val type = intent?.getStringExtra(EXTRA_REMINDER_TYPE)
                    ?.let { ReminderType.valueOfOrNull(it) }
                type?.let { showReminderSafely(it) }
            }

            ACTION_SHOW_WATER -> showReminderSafely(ReminderType.WATER)
            ACTION_SHOW_FOOD -> showReminderSafely(ReminderType.FOOD)
            ACTION_SHOW_REST -> showReminderSafely(ReminderType.REST)
            ACTION_SHOW_SLEEP -> showReminderSafely(ReminderType.SLEEP)
            ACTION_SHOW_RANDOM_TALK -> showRandomTalkSafely()
            ACTION_SHOW_FIRST_SUMMON -> showFirstSummonSafely()
            ACTION_DEBUG_BLINK -> showDebugBehaviorSafely(CatState.BLINKING)
            ACTION_DEBUG_YAWN -> showDebugBehaviorSafely(CatState.YAWNING)
            ACTION_DEBUG_LICKING -> showDebugBehaviorSafely(CatState.LICKING)
            ACTION_DEBUG_CURIOUS -> showDebugRelationshipSafely(ACTION_DEBUG_CURIOUS)
            ACTION_DEBUG_PEEK -> showDebugRelationshipSafely(ACTION_DEBUG_PEEK)
            ACTION_DEBUG_CUDDLE -> showDebugRelationshipSafely(ACTION_DEBUG_CUDDLE)
            ACTION_UPDATE_SIZE -> controller?.updateSize()
            ACTION_HIDE_TEMPORARILY -> controller?.hideTemporarily(TEMP_HIDE_DURATION_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "服务停止 onDestroy")
        controller?.hide()
        controller = null
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timeout: startId=$startId type=$fgsType")
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showReminderSafely(type: ReminderType) {
        Log.d(TAG, "收到 ${actionLogNameForType(type)}")
        if (OverlayPermissionHelper.canDrawOverlays(this)) {
            controller?.show()
            controller?.showReminder(type)
        } else {
            Log.w(TAG, "缺少悬浮窗权限，无法显示提醒: $type")
            stopSelf()
        }
    }

    private fun showRandomTalkSafely() {
        Log.d(TAG, "收到 ACTION_SHOW_RANDOM_TALK")
        if (OverlayPermissionHelper.canDrawOverlays(this)) {
            controller?.show()
            controller?.showRandomTalk()
        } else {
            Log.w(TAG, "缺少悬浮窗权限，无法显示随机陪伴文案")
            stopSelf()
        }
    }

    private fun showFirstSummonSafely() {
        Log.d(TAG, "收到 ACTION_SHOW_FIRST_SUMMON")
        if (OverlayPermissionHelper.canDrawOverlays(this)) {
            controller?.show()
            controller?.showFirstSummon()
        } else {
            Log.w(TAG, "缺少悬浮窗权限，无法显示首次召唤反馈")
            stopSelf()
        }
    }

    private fun showDebugBehaviorSafely(state: CatState) {
        if (!isDebugBuild()) {
            Log.w(TAG, "debug behavior ignored in release build: $state")
            return
        }
        Log.d(TAG, "收到 debug behavior=$state")
        if (OverlayPermissionHelper.canDrawOverlays(this)) {
            controller?.show()
            controller?.showDebugBehavior(state)
        } else {
            Log.w(TAG, "缺少悬浮窗权限，无法测试行为: $state")
            stopSelf()
        }
    }

    private fun showDebugRelationshipSafely(action: String) {
        if (!isDebugBuild()) {
            Log.w(TAG, "debug relationship behavior ignored in release build: $action")
            return
        }
        Log.d(TAG, "received $action")
        if (!OverlayPermissionHelper.canDrawOverlays(this)) {
            Log.w(TAG, "missing overlay permission for relationship behavior: $action")
            stopSelf()
            return
        }
        controller?.show()
        when (action) {
            ACTION_DEBUG_CURIOUS -> controller?.showDebugCurious()
            ACTION_DEBUG_PEEK -> controller?.showDebugPeek()
            ACTION_DEBUG_CUDDLE -> controller?.showDebugCuddle()
        }
    }

    private fun isDebugBuild(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        private const val TAG = "CatLifePet"
        private const val TEMP_HIDE_DURATION_MS = 30L * 60L * 1000L

        const val ACTION_START = "com.example.catlifepet.action.START"
        const val ACTION_STOP = "com.example.catlifepet.action.STOP"
        const val ACTION_REMINDER = "com.example.catlifepet.action.REMINDER"
        const val ACTION_SHOW_WATER = "com.example.catlifepet.action.SHOW_WATER"
        const val ACTION_SHOW_FOOD = "com.example.catlifepet.action.SHOW_FOOD"
        const val ACTION_SHOW_REST = "com.example.catlifepet.action.SHOW_REST"
        const val ACTION_SHOW_SLEEP = "com.example.catlifepet.action.SHOW_SLEEP"
        const val ACTION_SHOW_RANDOM_TALK = "com.example.catlifepet.action.SHOW_RANDOM_TALK"
        const val ACTION_SHOW_FIRST_SUMMON = "com.example.catlifepet.action.SHOW_FIRST_SUMMON"
        const val ACTION_DEBUG_BLINK = "com.example.catlifepet.action.DEBUG_BLINK"
        const val ACTION_DEBUG_YAWN = "com.example.catlifepet.action.DEBUG_YAWN"
        const val ACTION_DEBUG_LICKING = "com.example.catlifepet.action.DEBUG_LICKING"
        const val ACTION_DEBUG_CURIOUS = "com.example.catlifepet.action.DEBUG_CURIOUS"
        const val ACTION_DEBUG_PEEK = "com.example.catlifepet.action.DEBUG_PEEK"
        const val ACTION_DEBUG_CUDDLE = "com.example.catlifepet.action.DEBUG_CUDDLE"
        const val ACTION_UPDATE_SIZE = "com.example.catlifepet.action.UPDATE_SIZE"
        const val ACTION_HIDE_TEMPORARILY = "com.example.catlifepet.action.HIDE_TEMPORARILY"
        const val EXTRA_REMINDER_TYPE = "extra_reminder_type"
        @Volatile
        private var isRunning = false

        fun isRunning(): Boolean = isRunning

        fun start(context: Context) {
            val intent = Intent(context, CatFloatingService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "startForegroundService blocked", it) }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CatFloatingService::class.java).setAction(ACTION_STOP))
        }

        fun updateSize(context: Context) {
            sendActionIfServiceMayRun(context, ACTION_UPDATE_SIZE)
        }

        fun hideTemporarily(context: Context) {
            sendActionIfServiceMayRun(context, ACTION_HIDE_TEMPORARILY)
        }

        fun showReminder(context: Context, type: ReminderType) {
            val action = actionForType(type)
            val intent = Intent(context, CatFloatingService::class.java).setAction(action)
            if (isRunning) {
                runCatching { context.startService(intent) }
                    .onFailure {
                        Log.w(TAG, "running service reminder dispatch failed: $type", it)
                        NotificationUtils.showReminderFallback(context, type)
                    }
                return
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    Log.w(TAG, "background reminder service start blocked: $type", it)
                    NotificationUtils.showReminderFallback(context, type)
                }
        }

        fun showRandomTalk(context: Context) {
            sendAction(context, ACTION_SHOW_RANDOM_TALK)
        }

        fun showFirstSummon(context: Context) {
            sendActionIfServiceMayRun(context, ACTION_SHOW_FIRST_SUMMON)
        }

        fun showDebugBehavior(context: Context, state: CatState) {
            if (!context.isDebugBuild()) {
                Log.w(TAG, "debug behavior dispatch ignored in release build: $state")
                return
            }
            val action = when (state) {
                CatState.BLINKING -> ACTION_DEBUG_BLINK
                CatState.YAWNING -> ACTION_DEBUG_YAWN
                CatState.LICKING -> ACTION_DEBUG_LICKING
                else -> return
            }
            sendAction(context, action)
        }

        fun showDebugRelationshipBehavior(context: Context, action: String) {
            if (!context.isDebugBuild()) {
                Log.w(TAG, "debug relationship dispatch ignored in release build: $action")
                return
            }
            if (action == ACTION_DEBUG_CURIOUS || action == ACTION_DEBUG_PEEK || action == ACTION_DEBUG_CUDDLE) {
                sendAction(context, action)
            }
        }

        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, CatFloatingService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "startForegroundService failed: $action", it) }
        }

        private fun sendActionIfServiceMayRun(context: Context, action: String) {
            if (!isRunning) {
                Log.d(TAG, "optional action ignored because service is not running: $action")
                return
            }
            val intent = Intent(context, CatFloatingService::class.java).setAction(action)
            runCatching { context.startService(intent) }
                .onFailure { Log.d(TAG, "service not running for optional action=$action") }
        }

        private fun Context.isDebugBuild(): Boolean =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        fun actionForType(type: ReminderType): String {
            return when (type) {
                ReminderType.WATER -> ACTION_SHOW_WATER
                ReminderType.FOOD -> ACTION_SHOW_FOOD
                ReminderType.REST -> ACTION_SHOW_REST
                ReminderType.SLEEP -> ACTION_SHOW_SLEEP
            }
        }

        private fun actionLogNameForType(type: ReminderType): String {
            return when (type) {
                ReminderType.WATER -> "ACTION_SHOW_WATER"
                ReminderType.FOOD -> "ACTION_SHOW_FOOD"
                ReminderType.REST -> "ACTION_SHOW_REST"
                ReminderType.SLEEP -> "ACTION_SHOW_SLEEP"
            }
        }
    }
}
