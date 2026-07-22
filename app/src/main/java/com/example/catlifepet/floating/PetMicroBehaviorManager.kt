package com.example.catlifepet.floating

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.random.Random

/** Owns the single low-frequency micro-behavior loop. It never touches a View directly. */
class PetMicroBehaviorManager(context: Context, private val stateManager: CatStateManager, private val isPetVisible: () -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private val isDebugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var started = false
    private var generation = 0L
    private var scheduleRunnable: Runnable? = null
    private var transitionRunnable: Runnable? = null

    fun start() {
        if (Looper.myLooper() == Looper.getMainLooper()) startInternal() else handler.post { startInternal() }
    }

    fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) stopInternal() else handler.post { stopInternal() }
    }

    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
    }

    fun cancel(reason: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) cancelInternal(reason) else handler.post { cancelInternal(reason) }
    }

    private fun startInternal() {
        if (started) return
        started = true
        generation++
        scheduleNext()
        Log.d(TAG, "micro behavior scheduling started")
    }

    private fun stopInternal() {
        started = false
        generation++
        clearRunnables()
        if (stateManager.currentState == CatState.BLINKING) stateManager.switchTo(CatState.IDLE)
        Log.d(TAG, "micro behavior scheduling stopped")
    }

    private fun scheduleNext() {
        if (!started) return
        scheduleRunnable?.let(handler::removeCallbacks)
        val token = generation
        val runnable = Runnable {
            scheduleRunnable = null
            if (!started || token != generation) return@Runnable
            if (!isPetVisible() || stateManager.currentState != CatState.IDLE) {
                scheduleNext()
                return@Runnable
            }
            generation++
            val blinkToken = generation
            stateManager.switchTo(CatState.BLINKING)
            val transition = Runnable {
                transitionRunnable = null
                if (started && blinkToken == generation && stateManager.currentState == CatState.BLINKING) {
                    stateManager.switchTo(CatState.IDLE)
                    Log.d(TAG, "micro behavior finished: BLINKING")
                }
                scheduleNext()
            }
            transitionRunnable = transition
            handler.postDelayed(transition, CatState.BLINKING.defaultDurationMs)
            Log.d(TAG, "micro behavior triggered: BLINKING")
        }
        scheduleRunnable = runnable
        handler.postDelayed(runnable, nextDelayMillis())
    }

    private fun cancelInternal(reason: String) {
        generation++
        clearRunnables()
        if (stateManager.currentState == CatState.BLINKING) stateManager.switchTo(CatState.IDLE)
        if (started) scheduleNext()
        Log.d(TAG, "micro behavior cancelled: $reason")
    }

    private fun clearRunnables() {
        scheduleRunnable?.let(handler::removeCallbacks)
        transitionRunnable?.let(handler::removeCallbacks)
        scheduleRunnable = null
        transitionRunnable = null
    }

    private fun nextDelayMillis(): Long = if (isDebugBuild) Random.nextLong(2_000L, 5_001L) else Random.nextLong(6_000L, 20_001L)

    private companion object { const val TAG = "CatLifePet" }
}
