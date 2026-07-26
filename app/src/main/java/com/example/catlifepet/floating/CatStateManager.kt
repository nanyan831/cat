package com.example.catlifepet.floating

import android.util.Log

class CatStateManager(
    private val logger: (String) -> Unit = { Log.d(TAG, it) }
) {
    var currentState: CatState = CatState.IDLE
        private set

    private val listeners = mutableSetOf<(CatState) -> Unit>()

    fun addListener(listener: (CatState) -> Unit) {
        listeners.add(listener)
        listener(currentState)
    }

    fun removeListener(listener: (CatState) -> Unit) {
        listeners.removeAll { it === listener || it == listener }
    }

    fun switchTo(state: CatState) {
        if (currentState == state) {
            // Re-notify same-state triggers so ONCE animations can replay.
            listeners.forEach { it(state) }
            return
        }
        logger("小猫状态切换: $currentState -> $state")
        currentState = state
        listeners.forEach { it(state) }
    }

    private companion object {
        const val TAG = "CatLifePet"
    }
}

enum class CatState(
    val defaultBubble: String,
    val defaultDurationMs: Long
) {
    IDLE("", 0L),
    HAPPY("我在呢。", 1_200L),
    EATING("到饭点啦，好好吃饭。", 3_000L),
    DRINKING("喝口水吧，我陪你。", 3_000L),
    SLEEPING("已经很晚啦，该休息了。", 0L),
    STRETCHING("坐太久啦，起来活动一下。", 1_500L),
    DRAGGING("", 0L),
    BLINKING("", 450L),
    YAWNING("", 1_760L),
    // The frames total 1,620 ms; keep a small completion margin before returning to IDLE.
    CURIOUS("", 1_690L),
    // The frames total 1,680 ms; keep a small completion margin before returning to IDLE.
    CUDDLE("再靠近一点点吧。", 1_750L),
    // The frames total 1,880 ms; keep a small completion margin before returning to IDLE.
    LICKING("", 1_950L)
}
