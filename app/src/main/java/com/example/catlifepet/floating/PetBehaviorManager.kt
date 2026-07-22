package com.example.catlifepet.floating

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.catlifepet.data.SettingsRepository
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** Schedules autonomous state changes without owning animation or window concerns. */
class PetBehaviorManager(
    private val context: Context,
    private val stateManager: CatStateManager,
    private val petStatusManager: PetStatusManager,
    private val isPetVisible: () -> Boolean,
    private val onCuriousRequested: () -> Unit = {},
    private val onPeekRequested: () -> Boolean = { false }
) {
    private val handler = Handler(Looper.getMainLooper())
    private val settingsRepository = SettingsRepository(context.applicationContext)
    private val isDebugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private var started = false
    private var generation = 0L
    private var activeBehavior: AutonomousBehavior? = null
    private var expectedBehaviorState: CatState? = null
    private var nextBehaviorRunnable: Runnable? = null
    private var behaviorTransitionRunnable: Runnable? = null

    fun start() {
        runOnMain {
            if (started) return@runOnMain
            started = true
            generation++
            activeBehavior = null
            expectedBehaviorState = null
            debugLog("behavior scheduling started")
            scheduleNextBehaviorInternal()
        }
    }

    fun stop() {
        runOnMain {
            started = false
            generation++
            clearRunnables()
            activeBehavior = null
            expectedBehaviorState = null
            debugLog("behavior scheduling stopped")
        }
    }

    fun scheduleNextBehavior() {
        runOnMain { scheduleNextBehaviorInternal() }
    }

    fun cancelCurrentBehavior(reason: String) {
        runOnMain { cancelCurrentBehaviorInternal(reason, scheduleNext = true) }
    }

    fun notifyUserInteraction() {
        runOnMain { cancelCurrentBehaviorInternal("user interaction", scheduleNext = true) }
    }

    private fun scheduleNextBehaviorInternal() {
        if (!started) return
        nextBehaviorRunnable?.let(handler::removeCallbacks)
        nextBehaviorRunnable = null

        val delayMs = randomDelayMillis()
        val token = generation
        val runnable = Runnable {
            nextBehaviorRunnable = null
            if (!isTokenActive(token)) return@Runnable
            if (!isPetVisible()) {
                debugLog("skip behavior because pet is not visible")
                scheduleNextBehaviorInternal()
                return@Runnable
            }
            if (stateManager.currentState != CatState.IDLE) {
                debugLog("skip behavior because currentState=${stateManager.currentState}")
                scheduleNextBehaviorInternal()
                return@Runnable
            }
            petStatusManager.refreshEnergyFromElapsedTime()
            petStatusManager.refreshMoodFromElapsedTime()
            petStatusManager.refreshCompanionshipTime()
            startBehavior(selectBehavior())
        }
        nextBehaviorRunnable = runnable
        handler.postDelayed(runnable, delayMs)
        debugLog("schedule next behavior: ${delayMs}ms")
    }

    private fun startBehavior(behavior: AutonomousBehavior) {
        if (!started || stateManager.currentState != CatState.IDLE || !isPetVisible()) {
            debugLog("skip selected behavior because preconditions changed")
            scheduleNextBehaviorInternal()
            return
        }

        generation++
        val token = generation
        activeBehavior = behavior
        debugLog("selected behavior: $behavior")
        when (behavior) {
            AutonomousBehavior.NONE -> finishBehavior(token, expectedState = null)
            AutonomousBehavior.STRETCH -> {
                switchBehaviorState(CatState.STRETCHING)
                postTransition(token, STRETCH_DURATION_MS) {
                    finishBehavior(token, CatState.STRETCHING)
                }
            }
            AutonomousBehavior.HAPPY -> {
                switchBehaviorState(CatState.HAPPY)
                postTransition(token, HAPPY_DURATION_MS) {
                    if (!isBehaviorCurrent(token, CatState.HAPPY)) return@postTransition
                    petStatusManager.changeMood(1, PetStatusManager.MoodChangeReason.AUTONOMOUS_HAPPY)
                    finishBehavior(token, CatState.HAPPY)
                }
            }
            AutonomousBehavior.SHORT_SLEEP -> {
                debugLog("short sleep started")
                switchBehaviorState(CatState.STRETCHING)
                postTransition(token, STRETCH_DURATION_MS) {
                    if (!isBehaviorCurrent(token, CatState.STRETCHING)) return@postTransition
                    switchBehaviorState(CatState.SLEEPING)
                    postTransition(token, randomBetween(SHORT_SLEEP_MIN_MS, SHORT_SLEEP_MAX_MS)) {
                        if (!isBehaviorCurrent(token, CatState.SLEEPING)) return@postTransition
                        debugLog("short sleep finished")
                        petStatusManager.changeEnergy(5, PetStatusManager.EnergyChangeReason.SHORT_SLEEP)
                        petStatusManager.changeMood(1, PetStatusManager.MoodChangeReason.SHORT_SLEEP)
                        finishBehavior(token, CatState.SLEEPING)
                    }
                }
            }
            AutonomousBehavior.YAWN -> {
                switchBehaviorState(CatState.YAWNING)
                postTransition(token, YAWN_DURATION_MS) {
                    finishBehavior(token, CatState.YAWNING)
                }
            }
            AutonomousBehavior.LICKING -> {
                switchBehaviorState(CatState.LICKING)
                postTransition(token, LICKING_DURATION_MS) {
                    finishBehavior(token, CatState.LICKING)
                }
            }
            AutonomousBehavior.CURIOUS -> {
                debugLog("CURIOUS selected")
                onCuriousRequested()
                postTransition(token, CURIOUS_DURATION_MS) {
                    finishBehavior(token, expectedState = null)
                }
            }
            AutonomousBehavior.PEEK -> {
                if (!onPeekRequested()) {
                    debugLog("PEEK blocked by position or state")
                    finishBehavior(token, expectedState = null)
                } else {
                    debugLog("PEEK selected")
                    postTransition(token, PEEK_DURATION_MS) {
                        finishBehavior(token, expectedState = null)
                    }
                }
            }
        }
    }

    private fun finishBehavior(token: Long, expectedState: CatState?) {
        if (!isTokenActive(token)) return
        if (expectedState != null && stateManager.currentState != expectedState) {
            debugLog("behavior finish ignored: expected=$expectedState, actual=${stateManager.currentState}")
            return
        }
        if (expectedState != null) stateManager.switchTo(CatState.IDLE)
        activeBehavior = null
        expectedBehaviorState = null
        behaviorTransitionRunnable = null
        scheduleNextBehaviorInternal()
    }

    private fun cancelCurrentBehaviorInternal(reason: String, scheduleNext: Boolean) {
        if (!started) return
        val ownedState = expectedBehaviorState
        generation++
        clearRunnables()
        if (ownedState != null && stateManager.currentState == ownedState) {
            stateManager.switchTo(CatState.IDLE)
        }
        activeBehavior = null
        expectedBehaviorState = null
        debugLog("behavior cancelled: $reason")
        if (scheduleNext) scheduleNextBehaviorInternal()
    }

    private fun switchBehaviorState(state: CatState) {
        expectedBehaviorState = state
        stateManager.switchTo(state)
    }

    private fun postTransition(token: Long, delayMs: Long, action: () -> Unit) {
        behaviorTransitionRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable {
            behaviorTransitionRunnable = null
            if (!isTokenActive(token)) return@Runnable
            action()
        }
        behaviorTransitionRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun selectBehavior(): AutonomousBehavior {
        val energyLevel = petStatusManager.getEnergyLevel()
        val moodLevel = petStatusManager.getMoodLevel()
        val inDoNotDisturb = isInDoNotDisturbNow()
        val weights = weightsFor(energyLevel, moodLevel, inDoNotDisturb)
        val roll = Random.nextInt(100)
        debugLog(
            "behavior weights: energy=${petStatusManager.getEnergy()} level=$energyLevel " +
                "mood=${petStatusManager.getMood()} moodLevel=$moodLevel " +
                "dnd=$inDoNotDisturb weights=$weights roll=$roll"
        )
        return when {
            roll < weights.none -> AutonomousBehavior.NONE
            roll < weights.none + weights.stretch -> AutonomousBehavior.STRETCH
            roll < weights.none + weights.stretch + weights.happy -> AutonomousBehavior.HAPPY
            roll < weights.none + weights.stretch + weights.happy + weights.shortSleep -> AutonomousBehavior.SHORT_SLEEP
            roll < weights.none + weights.stretch + weights.happy + weights.shortSleep + weights.yawn -> AutonomousBehavior.YAWN
            roll < weights.none + weights.stretch + weights.happy + weights.shortSleep + weights.yawn + weights.licking -> AutonomousBehavior.LICKING
            roll < weights.none + weights.stretch + weights.happy + weights.shortSleep + weights.yawn + weights.licking + weights.curious -> AutonomousBehavior.CURIOUS
            else -> AutonomousBehavior.PEEK
        }
    }

    private fun weightsFor(
        energyLevel: PetStatusManager.EnergyLevel,
        moodLevel: PetStatusManager.MoodLevel,
        inDoNotDisturb: Boolean
    ): BehaviorWeights {
        val baseWeights = if (inDoNotDisturb) {
            when (energyLevel) {
                PetStatusManager.EnergyLevel.HIGH -> BehaviorWeights(69, 25, 0, 5, 1, 0, 0, 0)
                PetStatusManager.EnergyLevel.NORMAL -> BehaviorWeights(62, 20, 0, 15, 3, 0, 0, 0)
                PetStatusManager.EnergyLevel.LOW -> BehaviorWeights(48, 25, 0, 22, 5, 0, 0, 0)
                PetStatusManager.EnergyLevel.EXHAUSTED -> BehaviorWeights(30, 20, 0, 40, 10, 0, 0, 0)
            }
        } else {
            when (energyLevel) {
                PetStatusManager.EnergyLevel.HIGH -> BehaviorWeights(50, 15, 25, 5, 1, 4, 0, 0)
                PetStatusManager.EnergyLevel.NORMAL -> BehaviorWeights(50, 20, 15, 10, 2, 3, 0, 0)
                PetStatusManager.EnergyLevel.LOW -> BehaviorWeights(43, 25, 5, 22, 5, 0, 0, 0)
                PetStatusManager.EnergyLevel.EXHAUSTED -> BehaviorWeights(25, 20, 0, 45, 10, 0, 0, 0)
            }
        }
        val growth = GrowthUnlockManager(context)
        val curious = if (!inDoNotDisturb && energyLevel != PetStatusManager.EnergyLevel.EXHAUSTED &&
            growth.isUnlocked(GrowthUnlockManager.UnlockFeature.CURIOUS_BEHAVIOR)) {
            when (growth.currentLevel()) {
                PetStatusManager.AffectionLevel.FAMILIAR -> 3
                PetStatusManager.AffectionLevel.CLOSE -> 5
                PetStatusManager.AffectionLevel.BONDED -> 7
                PetStatusManager.AffectionLevel.NEW -> 0
            }
        } else 0
        val peek = if (!inDoNotDisturb && energyLevel != PetStatusManager.EnergyLevel.EXHAUSTED &&
            growth.isUnlocked(GrowthUnlockManager.UnlockFeature.PEEK_BEHAVIOR)) {
            when (growth.currentLevel()) {
                PetStatusManager.AffectionLevel.CLOSE -> 4
                PetStatusManager.AffectionLevel.BONDED -> 6
                else -> 0
            }
        } else 0
        val withRelationship = baseWeights.copy(none = baseWeights.none - curious - peek, curious = curious, peek = peek)
        val adjustedWeights = when {
            inDoNotDisturb || energyLevel == PetStatusManager.EnergyLevel.EXHAUSTED -> withRelationship
            moodLevel == PetStatusManager.MoodLevel.HAPPY -> {
                val boost = minOf(10, withRelationship.happy, withRelationship.none)
                withRelationship.copy(none = withRelationship.none - boost, happy = withRelationship.happy + boost)
            }
            moodLevel == PetStatusManager.MoodLevel.LOW -> {
                val reduction = minOf(10, withRelationship.happy)
                withRelationship.copy(
                    none = withRelationship.none + reduction + withRelationship.licking,
                    happy = withRelationship.happy - reduction,
                    licking = 0
                )
            }
            moodLevel == PetStatusManager.MoodLevel.SAD -> {
                withRelationship.copy(
                    none = withRelationship.none + withRelationship.happy + withRelationship.licking,
                    happy = 0,
                    licking = 0
                )
            }
            else -> withRelationship
        }
        check(adjustedWeights.total == 100)
        check(
            adjustedWeights.none >= 0 && adjustedWeights.stretch >= 0 &&
                adjustedWeights.happy >= 0 && adjustedWeights.shortSleep >= 0 &&
                adjustedWeights.yawn >= 0 && adjustedWeights.licking >= 0 &&
                adjustedWeights.curious >= 0 && adjustedWeights.peek >= 0
        )
        return adjustedWeights
    }

    private fun randomDelayMillis(): Long {
        if (isDebugBuild) return randomBetween(DEBUG_MIN_DELAY_MS, DEBUG_MAX_DELAY_MS)
        return if (isInDoNotDisturbNow()) {
            randomBetween(DND_MIN_DELAY_MS, DND_MAX_DELAY_MS)
        } else {
            randomBetween(NORMAL_MIN_DELAY_MS, NORMAL_MAX_DELAY_MS)
        }
    }

    private fun isInDoNotDisturbNow(): Boolean {
        val settings = settingsRepository.getSettings()
        if (!settings.doNotDisturbEnabled) return false
        return runCatching {
            val formatter = DateTimeFormatter.ofPattern("H:mm")
            val start = LocalTime.parse(settings.doNotDisturbStart, formatter)
            val end = LocalTime.parse(settings.doNotDisturbEnd, formatter)
            val now = LocalTime.now()
            when {
                start == end -> true
                start.isBefore(end) -> !now.isBefore(start) && now.isBefore(end)
                else -> !now.isBefore(start) || now.isBefore(end)
            }
        }.onFailure {
            debugLog("invalid do-not-disturb time; using normal behavior frequency")
        }.getOrDefault(false)
    }

    private fun isBehaviorCurrent(token: Long, expectedState: CatState): Boolean {
        return isTokenActive(token) &&
            activeBehavior != null &&
            expectedBehaviorState == expectedState &&
            stateManager.currentState == expectedState
    }

    private fun isTokenActive(token: Long): Boolean = started && generation == token

    private fun clearRunnables() {
        nextBehaviorRunnable?.let(handler::removeCallbacks)
        behaviorTransitionRunnable?.let(handler::removeCallbacks)
        nextBehaviorRunnable = null
        behaviorTransitionRunnable = null
    }

    private fun randomBetween(minInclusive: Long, maxInclusive: Long): Long {
        return Random.nextLong(minInclusive, maxInclusive + 1L)
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    private fun debugLog(message: String) {
        if (isDebugBuild) Log.d(TAG, message)
    }

    private enum class AutonomousBehavior {
        NONE,
        STRETCH,
        HAPPY,
        SHORT_SLEEP,
        YAWN,
        LICKING,
        CURIOUS,
        PEEK
    }

    private data class BehaviorWeights(
        val none: Int,
        val stretch: Int,
        val happy: Int,
        val shortSleep: Int,
        val yawn: Int,
        val licking: Int,
        val curious: Int,
        val peek: Int
    ) {
        val total: Int = none + stretch + happy + shortSleep + yawn + licking + curious + peek
    }

    private companion object {
        const val TAG = "CatLifePet"

        const val NORMAL_MIN_DELAY_MS = 40_000L
        const val NORMAL_MAX_DELAY_MS = 120_000L
        const val DEBUG_MIN_DELAY_MS = 5_000L
        const val DEBUG_MAX_DELAY_MS = 15_000L
        const val DND_MIN_DELAY_MS = 120_000L
        const val DND_MAX_DELAY_MS = 300_000L
        const val STRETCH_DURATION_MS = 1_500L
        const val HAPPY_DURATION_MS = 1_200L
        const val SHORT_SLEEP_MIN_MS = 8_000L
        const val SHORT_SLEEP_MAX_MS = 20_000L
        const val YAWN_DURATION_MS = 1_760L
        const val LICKING_DURATION_MS = 1_950L
        const val CURIOUS_DURATION_MS = 1_690L
        const val PEEK_DURATION_MS = 2_400L
    }
}
