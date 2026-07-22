package com.example.catlifepet.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.example.catlifepet.data.SettingsRepository
import com.example.catlifepet.reminder.ReminderType
import com.example.catlifepet.util.ScreenUtils
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class PetWindowController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val settingsRepository = SettingsRepository(context)
    private val stateManager = CatStateManager()
    private val petStatusManager = PetStatusManager.getInstance(context)
    private val growthUnlockManager = GrowthUnlockManager(context)
    private val handler = Handler(Looper.getMainLooper())
    private val behaviorManager = PetBehaviorManager(
        context,
        stateManager,
        petStatusManager,
        { catPetView != null && !temporarilyHidden && activePeekAnimator == null && !curiousActive },
        { showCuriousBehavior() },
        { startAutonomousPeek() }
    )
    private val microBehaviorManager = PetMicroBehaviorManager(context, stateManager) {
        catPetView != null && !temporarilyHidden && activePeekAnimator == null && !curiousActive
    }

    private var catPetView: CatPetView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var windowWidth = 0
    private var windowHeight = 0
    private var currentPetSizePx = 0
    private var temporarilyHidden = false
    private var officialSleepActive = false
    private var officialSleepRewardGranted = false
    private var activePeekAnimator: ValueAnimator? = null
    private var peekAnchorX: Int? = null
    private var curiousActive = false
    private var curiousGeneration = 0L

    fun show() {
        val hiddenUntil = settingsRepository.getTemporaryHideUntil()
        val remainingHideMillis = hiddenUntil - System.currentTimeMillis()
        if (remainingHideMillis > 0L) {
            temporarilyHidden = true
            scheduleTemporaryRestore(remainingHideMillis)
            Log.d(TAG, "show skipped: pet is temporarily hidden for ${remainingHideMillis}ms")
            return
        }
        if (hiddenUntil > 0L) settingsRepository.clearTemporaryHide()
        temporarilyHidden = false
        if (catPetView != null) {
            Log.d(TAG, "addView skipped: pet already showing")
            return
        }

        val settings = settingsRepository.getSettings()
        petStatusManager.refreshEnergyFromElapsedTime()
        petStatusManager.refreshMoodFromElapsedTime()
        currentPetSizePx = ScreenUtils.dp(context, settings.petSizeDp)
        updateWindowSize(currentPetSizePx)

        val params = WindowManager.LayoutParams(
            windowWidth,
            windowHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = sanitizeRestoredX(
                if (settings.lastX >= -peekOffset()) settings.lastX else ScreenUtils.screenWidth(context) - windowWidth
            )
            y = sanitizeY(
                if (settings.lastY >= 0) settings.lastY else (ScreenUtils.screenHeight(context) - windowHeight) / 2
            )
        }

        stateManager.switchTo(CatState.IDLE)
        val view = CatPetView(
            context,
            currentPetSizePx,
            stateManager,
            object : CatPetView.Callbacks {
                override fun onDragBy(dx: Int, dy: Int) {
                    cancelPeek(restoreAnchor = false)
                    moveBy(dx, dy)
                }

                override fun onDragEnd() {
                    snapToEdge()
                }

                override fun onClickPet() {
                    viewClicked()
                }

                override fun onUserInteractionStarted(kind: CatPetView.UserInteraction) {
                    cancelCurious()
                    cancelPeek(restoreAnchor = kind == CatPetView.UserInteraction.CLICK)
                    behaviorManager.notifyUserInteraction()
                    microBehaviorManager.cancel("user interaction: $kind")
                    if (kind == CatPetView.UserInteraction.DRAG && officialSleepActive) {
                        officialSleepActive = false
                    }
                }

                override fun onReminderConfirmed(type: ReminderType, stateAtConfirmation: CatState) {
                    handleReminderConfirmation(type, stateAtConfirmation)
                }
            }
        )

        view.setCatOnLeft(params.x <= (ScreenUtils.screenWidth(context) - windowWidth) / 2)
        try {
            windowManager.addView(view, params)
        } catch (error: RuntimeException) {
            view.release()
            layoutParams = null
            catPetView = null
            Log.e(TAG, "addView failed", error)
            return
        }
        layoutParams = params
        catPetView = view
        OverlayLifecycleDiagnostics.onViewAdded()
        Log.d(TAG, "addView: x=${params.x}, y=${params.y}, width=$windowWidth, height=$windowHeight")
        petStatusManager.onPetVisible()
        behaviorManager.start()
        microBehaviorManager.start()
        showRelationshipFeedbackIfSafe()
    }

    fun hide() {
        handler.removeCallbacksAndMessages(TEMP_HIDE_TOKEN)
        cancelCurious()
        cancelPeek(restoreAnchor = false)
        temporarilyHidden = false
        microBehaviorManager.stop()
        removeCurrentView()
    }

    fun updateSize() {
        val view = catPetView
        if (view == null || layoutParams == null) {
            Log.d(TAG, "pet size changed while service has no visible pet")
            return
        }
        val params = layoutParams ?: return
        val oldWindowWidth = windowWidth
        val oldWindowHeight = windowHeight
        val oldPetSize = currentPetSizePx
        currentPetSizePx = ScreenUtils.dp(context, settingsRepository.getSettings().petSizeDp)
        updateWindowSize(currentPetSizePx)
        params.width = windowWidth
        params.height = windowHeight
        params.x = adjustXAfterSizeChange(params.x, oldWindowWidth, oldPetSize)
        params.y = sanitizeY(params.y + (oldWindowHeight - windowHeight) / 2)
        view.updatePetSize(currentPetSizePx)
        view.setCatOnLeft(isLeftSide(params.x))
        windowManager.updateViewLayout(view, params)
        settingsRepository.savePetPosition(params.x, params.y)
        Log.d(TAG, "pet size changed: petSizePx=$currentPetSizePx, x=${params.x}, y=${params.y}")
    }

    fun hideTemporarily(durationMs: Long) {
        val hiddenUntil = System.currentTimeMillis() + durationMs
        settingsRepository.saveTemporaryHideUntil(hiddenUntil)
        temporarilyHidden = true
        cancelCurious()
        cancelPeek(restoreAnchor = false)
        removeCurrentView()
        Log.d(TAG, "pet hidden temporarily")
        scheduleTemporaryRestore(durationMs)
    }

    private fun scheduleTemporaryRestore(delayMillis: Long) {
        handler.removeCallbacksAndMessages(TEMP_HIDE_TOKEN)
        handler.postAtTime({
            settingsRepository.clearTemporaryHide()
            temporarilyHidden = false
            Log.d(TAG, "pet restored after temporary hide")
            show()
        }, TEMP_HIDE_TOKEN, android.os.SystemClock.uptimeMillis() + delayMillis.coerceAtLeast(1L))
    }

    private fun removeCurrentView() {
        cancelCurious()
        cancelPeek(restoreAnchor = false)
        behaviorManager.stop()
        microBehaviorManager.stop()
        officialSleepActive = false
        officialSleepRewardGranted = false
        val view = catPetView ?: return
        petStatusManager.onPetHidden()
        runCatching { windowManager.removeView(view) }
            .onSuccess { Log.d(TAG, "removeView") }
            .onFailure { Log.w(TAG, "removeView failed", it) }
        view.release()
        catPetView = null
        layoutParams = null
        OverlayLifecycleDiagnostics.onViewRemoved()
    }

    fun showReminder(type: ReminderType) {
        cancelCurious()
        cancelPeek(restoreAnchor = true)
        behaviorManager.cancelCurrentBehavior("reminder: $type")
        microBehaviorManager.cancel("reminder: $type")
        val view = catPetView ?: return
        officialSleepActive = type == ReminderType.SLEEP
        officialSleepRewardGranted = false
        when (type) {
            ReminderType.WATER -> view.showReminderState(
                ReminderType.WATER,
                CatState.DRINKING,
                DialogueManager.randomMessage(CatState.DRINKING),
                requireNotNull(DialogueManager.confirmMessage(CatState.DRINKING))
            )
            ReminderType.FOOD -> view.showReminderState(
                ReminderType.FOOD,
                CatState.EATING,
                DialogueManager.randomMessage(CatState.EATING),
                requireNotNull(DialogueManager.confirmMessage(CatState.EATING))
            )
            ReminderType.REST -> view.showReminderState(
                ReminderType.REST,
                CatState.STRETCHING,
                DialogueManager.randomMessage(CatState.STRETCHING),
                requireNotNull(DialogueManager.confirmMessage(CatState.STRETCHING)),
                targetState = CatState.IDLE,
                delayMillis = CatState.STRETCHING.defaultDurationMs
            )
            ReminderType.SLEEP -> view.showReminderState(
                ReminderType.SLEEP,
                CatState.STRETCHING,
                DialogueManager.randomMessage(CatState.SLEEPING),
                requireNotNull(DialogueManager.confirmMessage(CatState.SLEEPING)),
                targetState = CatState.SLEEPING,
                delayMillis = CatState.STRETCHING.defaultDurationMs
            )
        }
    }

    fun showRandomTalk() {
        cancelCurious()
        cancelPeek(restoreAnchor = true)
        behaviorManager.cancelCurrentBehavior("external random talk")
        microBehaviorManager.cancel("external random talk")
        petStatusManager.refreshMoodFromElapsedTime()
        petStatusManager.refreshCompanionshipTime()
        syncGrowth()
        catPetView?.showCompanionMessage(
            DialogueManager.randomClickMessage(
                petStatusManager.getMoodLevel(),
                petStatusManager.getAffectionLevel(),
                growthUnlockManager.unlockedFeatures().toSet()
            )
        )
        schedulePendingFeedback()
    }

    fun showFirstSummon() {
        cancelCurious()
        cancelPeek(restoreAnchor = true)
        behaviorManager.cancelCurrentBehavior("first summon")
        microBehaviorManager.cancel("first summon")
        catPetView?.showCompanionMessage("终于见到你啦～")
        stateManager.switchTo(CatState.HAPPY)
        handler.postDelayed({
            if (catPetView != null && stateManager.currentState == CatState.HAPPY) {
                stateManager.switchTo(CatState.IDLE)
            }
        }, 2_500L)
        Log.d(TAG, "first summon feedback shown")
    }

    fun showDebugBehavior(state: CatState) {
        if (state !in setOf(CatState.BLINKING, CatState.YAWNING, CatState.LICKING)) return
        behaviorManager.cancelCurrentBehavior("debug behavior: $state")
        cancelCurious()
        cancelPeek(restoreAnchor = true)
        microBehaviorManager.cancel("debug behavior: $state")
        val token = System.currentTimeMillis()
        stateManager.switchTo(state)
        handler.postDelayed({
            if (catPetView != null && stateManager.currentState == state) {
                stateManager.switchTo(CatState.IDLE)
                Log.d(TAG, "debug behavior finished: $state token=$token")
            }
        }, state.defaultDurationMs)
        Log.d(TAG, "debug behavior triggered: $state token=$token")
    }

    fun showDebugCurious() {
        if (!growthUnlockManager.isUnlocked(GrowthUnlockManager.UnlockFeature.CURIOUS_BEHAVIOR)) {
            Log.d(TAG, "CURIOUS blocked by unlock")
            return
        }
        behaviorManager.cancelCurrentBehavior("debug CURIOUS")
        cancelCurious()
        cancelPeek(restoreAnchor = true)
        microBehaviorManager.cancel("debug CURIOUS")
        showCuriousBehavior()
    }

    fun showDebugPeek() {
        if (!growthUnlockManager.isUnlocked(GrowthUnlockManager.UnlockFeature.PEEK_BEHAVIOR)) {
            Log.d(TAG, "PEEK blocked by unlock")
            return
        }
        behaviorManager.cancelCurrentBehavior("debug PEEK")
        if (!startAutonomousPeek()) Log.d(TAG, "PEEK requires edge position")
    }

    fun showDebugCuddle() {
        if (!growthUnlockManager.isUnlocked(GrowthUnlockManager.UnlockFeature.CUDDLE_BEHAVIOR)) {
            Log.d(TAG, "CUDDLE blocked by unlock")
            return
        }
        behaviorManager.cancelCurrentBehavior("debug CUDDLE")
        cancelCurious()
        cancelPeek(restoreAnchor = true)
        microBehaviorManager.cancel("debug CUDDLE")
        val mood = petStatusManager.getMoodLevel()
        catPetView?.showCuddleMessage(DialogueManager.randomCuddleMessage(mood))
        Log.d(TAG, "CUDDLE triggered (debug, no stat changes, edge-independent frames)")
    }

    private fun moveBy(dx: Int, dy: Int) {
        val params = layoutParams ?: return
        val view = catPetView ?: return
        params.x = (params.x + dx).coerceIn(minPeekX(), maxPeekX())
        params.y = sanitizeY(params.y + dy)
        windowManager.updateViewLayout(view, params)
    }

    private fun snapToEdge() {
        val params = layoutParams ?: return
        val view = catPetView ?: return
        val leftSide = isLeftSide(params.x)
        params.x = if (leftSide) minPeekX() else maxPeekX()
        params.y = sanitizeY(params.y)
        view.setCatOnLeft(leftSide)
        windowManager.updateViewLayout(view, params)
        settingsRepository.savePetPosition(params.x, params.y)
        Log.d(TAG, "snap peek edge: left=$leftSide, x=${params.x}, y=${params.y}, hidden=${peekOffset()}px")
    }

    private fun viewClicked() {
        cancelPeek(restoreAnchor = true)
        microBehaviorManager.cancel("pet click")
        petStatusManager.refreshCompanionshipTime()
        petStatusManager.applyClickEnergyCost()
        petStatusManager.applyClickMoodReward()
        petStatusManager.tryGainDailyInteractionAffection()
        syncGrowth()
        val features = growthUnlockManager.unlockedFeatures().toSet()
        val decision = DialogueManager.randomClickDecision(
            petStatusManager.getMoodLevel(),
            petStatusManager.getAffectionLevel(),
            features,
            allowCuddle = true
        )
        when (decision.state) {
            CatState.CUDDLE -> catPetView?.showCuddleMessage(decision.message)
            else -> catPetView?.showCompanionMessage(decision.message)
        }
        schedulePendingFeedback()
    }

    private fun handleReminderConfirmation(type: ReminderType, stateAtConfirmation: CatState) {
        microBehaviorManager.cancel("reminder confirmation")
        petStatusManager.refreshCompanionshipTime()
        when (type) {
            ReminderType.WATER -> {
                petStatusManager.changeEnergy(2, PetStatusManager.EnergyChangeReason.DRINK_COMPLETED)
                petStatusManager.changeMood(2, PetStatusManager.MoodChangeReason.DRINK_COMPLETED)
                petStatusManager.tryGainReminderAffection()
            }
            ReminderType.FOOD -> {
                petStatusManager.changeEnergy(5, PetStatusManager.EnergyChangeReason.EAT_COMPLETED)
                petStatusManager.changeMood(3, PetStatusManager.MoodChangeReason.EAT_COMPLETED)
                petStatusManager.tryGainReminderAffection()
            }
            ReminderType.REST -> {
                petStatusManager.changeEnergy(3, PetStatusManager.EnergyChangeReason.REST_COMPLETED)
                petStatusManager.changeMood(2, PetStatusManager.MoodChangeReason.REST_COMPLETED)
                petStatusManager.tryGainReminderAffection()
            }
            ReminderType.SLEEP -> {
                if (officialSleepActive && !officialSleepRewardGranted && stateAtConfirmation == CatState.SLEEPING) {
                    officialSleepRewardGranted = true
                    petStatusManager.changeEnergy(20, PetStatusManager.EnergyChangeReason.OFFICIAL_SLEEP)
                    petStatusManager.changeMood(3, PetStatusManager.MoodChangeReason.OFFICIAL_SLEEP)
                    petStatusManager.tryGainOfficialSleepAffection()
                }
                officialSleepActive = false
            }
        }
        syncGrowth()
        schedulePendingFeedback()
    }

    private fun syncGrowth() {
        growthUnlockManager.syncWithAffection(petStatusManager.getAffectionLevel())
    }

    private fun showCuriousBehavior() {
        if (!growthUnlockManager.isUnlocked(GrowthUnlockManager.UnlockFeature.CURIOUS_BEHAVIOR) ||
            stateManager.currentState != CatState.IDLE || officialSleepActive) {
            Log.d(TAG, "CURIOUS blocked by state or unlock")
            return
        }
        microBehaviorManager.cancel("CURIOUS selected")
        curiousGeneration++
        val token = curiousGeneration
        curiousActive = true
        stateManager.switchTo(CatState.CURIOUS)
        handler.postDelayed({
            if (curiousGeneration == token) {
                curiousActive = false
                if (stateManager.currentState == CatState.CURIOUS) {
                    stateManager.switchTo(CatState.IDLE)
                }
                Log.d(TAG, "CURIOUS animation finished")
            }
        }, CURIOUS_DURATION_MS)
        val mood = petStatusManager.getMoodLevel()
        val message = DialogueManager.randomCuriousMessage(mood, growthUnlockManager.currentLevel())
        catPetView?.showPassiveMessage(message)
        Log.d(TAG, "CURIOUS selected")
    }

    private fun cancelCurious() {
        if (!curiousActive) return
        curiousGeneration++
        curiousActive = false
        if (stateManager.currentState == CatState.CURIOUS) {
            stateManager.switchTo(CatState.IDLE)
        }
        Log.d(TAG, "CURIOUS cancelled")
    }

    private fun startAutonomousPeek(): Boolean {
        val params = layoutParams ?: return false
        val view = catPetView ?: return false
        if (!growthUnlockManager.isUnlocked(GrowthUnlockManager.UnlockFeature.PEEK_BEHAVIOR) ||
            temporarilyHidden || officialSleepActive || stateManager.currentState != CatState.IDLE ||
            !isAtPeekEdge(params.x)
        ) return false
        cancelPeek(restoreAnchor = true)
        val anchor = params.x
        val leftSide = isLeftSide(anchor)
        val travel = (peekOffset() * 0.5f).toInt().coerceAtLeast(1)
        // Autonomous PEEK temporarily goes beyond the saved 25% edge anchor.
        val temporaryOffset = (currentPetSizePx * 0.5f).toInt()
        val temporaryMinX = -temporaryOffset
        val temporaryMaxX = ScreenUtils.screenWidth(context) - windowWidth + temporaryOffset
        val target = if (leftSide) (anchor - travel).coerceAtLeast(temporaryMinX)
        else (anchor + travel).coerceAtMost(temporaryMaxX)
        peekAnchorX = anchor
        val animator = ValueAnimator.ofInt(anchor, target, anchor).apply {
            duration = PEEK_ANIMATION_DURATION_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                if (catPetView !== view || layoutParams == null) return@addUpdateListener
                params.x = valueAnimator.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
                    .onFailure { Log.w(TAG, "PEEK update skipped", it); cancel() }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    val isCurrentAnimator = activePeekAnimator === this@apply
                    if (isCurrentAnimator && catPetView === view && layoutParams != null) {
                        params.x = anchor
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    if (isCurrentAnimator) {
                        activePeekAnimator = null
                        peekAnchorX = null
                        Log.d(TAG, "PEEK finished: left=$leftSide, anchor=$anchor, endX=${params.x}")
                    }
                }
            })
        }
        activePeekAnimator = animator
        microBehaviorManager.cancel("PEEK selected")
        animator.start()
        Log.d(TAG, "PEEK started: left=$leftSide, anchor=$anchor, target=$target")
        return true
    }

    private fun cancelPeek(restoreAnchor: Boolean) {
        val animator = activePeekAnimator ?: return
        val params = layoutParams
        val currentX = params?.x
        val anchor = peekAnchorX
        activePeekAnimator = null
        animator.cancel()
        peekAnchorX = null
        if (restoreAnchor && anchor != null) {
            val view = catPetView
            if (params != null && view != null) {
                params.x = anchor.coerceIn(minPeekX(), maxPeekX())
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        Log.d(
            TAG,
            "PEEK animator cancelled: restore=$restoreAnchor, currentX=$currentX, anchor=$anchor, endX=${params?.x}"
        )
    }

    private fun isAtPeekEdge(x: Int): Boolean {
        val threshold = (peekOffset() * 2).coerceAtLeast(ScreenUtils.dp(context, 8))
        return x <= minPeekX() + threshold || x >= maxPeekX() - threshold
    }

    private fun showRelationshipFeedbackIfSafe() {
        syncGrowth()
        val view = catPetView ?: return
        if (stateManager.currentState != CatState.IDLE) return

        val pending = growthUnlockManager.getPendingLevelUp()
        if (pending != null) {
            view.showCompanionMessage(
                DialogueManager.relationshipLevelUpMessage(pending.newLevel)
            )
            growthUnlockManager.markPendingLevelUpShown()
            return
        }

        if (growthUnlockManager.canShowDailyWelcome(isInDoNotDisturbNow())) {
            view.showCompanionMessage(
                DialogueManager.relationshipWelcomeMessage(
                    petStatusManager.getMoodLevel(),
                    growthUnlockManager.currentLevel()
                )
            )
            growthUnlockManager.markDailyWelcomeShown()
        }
    }

    private fun schedulePendingFeedback() {
        handler.postDelayed({ showRelationshipFeedbackIfSafe() }, 2_500L)
    }

    private fun isInDoNotDisturbNow(): Boolean {
        val settings = settingsRepository.getSettings()
        if (!settings.doNotDisturbEnabled) return false
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val start = runCatching { LocalTime.parse(settings.doNotDisturbStart, formatter) }
            .getOrElse { LocalTime.of(23, 30) }
        val end = runCatching { LocalTime.parse(settings.doNotDisturbEnd, formatter) }
            .getOrElse { LocalTime.of(7, 30) }
        val now = LocalTime.now()
        return if (start == end) true else if (start.isBefore(end) || start == end) {
            !now.isBefore(start) && now.isBefore(end)
        } else {
            !now.isBefore(start) || now.isBefore(end)
        }
    }

    private fun updateWindowSize(petSizePx: Int) {
        windowWidth = petSizePx + ScreenUtils.dp(context, 196)
        windowHeight = petSizePx + ScreenUtils.dp(context, 24)
    }

    private fun adjustXAfterSizeChange(oldX: Int, oldWindowWidth: Int, oldPetSize: Int): Int {
        val oldScreenRight = oldX + oldWindowWidth
        val screenWidth = ScreenUtils.screenWidth(context)
        return if (oldScreenRight >= screenWidth - oldPetSize / 4) {
            maxPeekX()
        } else if (oldX <= -(oldPetSize / 4)) {
            minPeekX()
        } else {
            oldX.coerceIn(minPeekX(), maxPeekX())
        }
    }

    private fun sanitizeRestoredX(x: Int): Int = x.coerceIn(minPeekX(), maxPeekX())

    private fun sanitizeY(y: Int): Int = y.coerceIn(0, maxY())

    private fun isLeftSide(x: Int): Boolean = x + windowWidth / 2 < ScreenUtils.screenWidth(context) / 2

    private fun peekOffset(): Int = (currentPetSizePx * 0.25f).toInt().coerceAtLeast(0)

    private fun minPeekX(): Int = -peekOffset()

    private fun maxPeekX(): Int = (ScreenUtils.screenWidth(context) - windowWidth + peekOffset()).coerceAtLeast(minPeekX())

    private fun maxY(): Int = (ScreenUtils.screenHeight(context) - windowHeight).coerceAtLeast(0)

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private companion object {
        const val TAG = "CatLifePet"
        private val TEMP_HIDE_TOKEN = Any()
        const val PEEK_ANIMATION_DURATION_MS = 2_400L
        const val CURIOUS_DURATION_MS = 1_690L
    }
}
