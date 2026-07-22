package com.example.catlifepet.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                ReminderManager(context).syncAll()
                Log.d(TAG, "提醒已因系统事件重排: action=${intent.action}")
            } catch (error: Throwable) {
                Log.e(TAG, "系统事件提醒重排失败: action=${intent.action}", error)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object { const val TAG = "CatLifePet" }
}
