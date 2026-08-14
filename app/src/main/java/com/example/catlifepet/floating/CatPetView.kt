package com.example.catlifepet.floating

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.catlifepet.reminder.ReminderType
import com.example.catlifepet.util.ScreenUtils
import kotlin.math.abs
import kotlin.math.hypot

class CatPetView(
    context: Context,
    private var petSizePx: Int,
    private val stateManager: CatStateManager,
    private val callbacks: Callbacks
) : FrameLayout(context) {
    constructor(context: Context) : this(context, 0, CatStateManager(), NoOpCallbacks)

    constructor(context: Context, attrs: AttributeSet?) : this(context)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(context)

    interface Callbacks {
        fun onDragBy(dx: Int, dy: Int)
        fun onDragEnd()
        fun onClickPet()
        fun onUserInteractionStarted(kind: UserInteraction)
        fun onReminderConfirmed(type: ReminderType, stateAtConfirmation: CatState)
    }

    enum class UserInteraction {
        CLICK,
        DRAG
    }

    private val handler = Handler(Looper.getMainLooper())
    private val catImage = PetImageView(context)
    private val animationManager = CatAnimationManager(catImage)
    private val bubbleView = PetBubbleView(context)
    private val touchSlopPx = ScreenUtils.dp(context, 10)

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var catOnLeft = false
    private var pendingStateRunnable: Runnable? = null
    private var activeReminderConfirmation: String? = null
    private var activeReminderType: ReminderType? = null

    private val stateListener: (CatState) -> Unit = animationManager::play

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
        catImage.setBackgroundColor(Color.TRANSPARENT)
        catImage.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(catImage)
        addView(bubbleView)
        setCatOnLeft(false)
        stateManager.addListener(stateListener)
        installTouch()
        Log.d(TAG, "CatPetView uses one transparent FIT_CENTER ImageView for all PNG states")
    }

    fun setCatOnLeft(isLeft: Boolean) {
        catOnLeft = isLeft
        val catParams = LayoutParams(petSizePx, petSizePx).apply {
            gravity = if (isLeft) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL
        }
        catImage.layoutParams = catParams

        val bubbleWidth = ScreenUtils.dp(context, 176)
        val bubbleParams = LayoutParams(bubbleWidth, LayoutParams.WRAP_CONTENT).apply {
            gravity = if (isLeft) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            val margin = ScreenUtils.dp(context, 8)
            if (isLeft) leftMargin = petSizePx + margin else rightMargin = petSizePx + margin
        }
        bubbleView.layoutParams = bubbleParams
    }

    fun updatePetSize(newPetSizePx: Int) {
        petSizePx = newPetSizePx
        setCatOnLeft(catOnLeft)
        requestLayout()
    }

    fun showCompanionMessage(message: String) {
        activeReminderConfirmation = null
        activeReminderType = null
        setCatState(CatState.HAPPY)
        showBubble(message)
        scheduleStateTransition(CatState.HAPPY, CatState.IDLE, CatState.HAPPY.defaultDurationMs)
    }

    fun showCuddleMessage(message: String) {
        activeReminderConfirmation = null
        activeReminderType = null
        setCatState(CatState.CUDDLE)
        showBubble(message)
        scheduleStateTransition(CatState.CUDDLE, CatState.IDLE, CatState.CUDDLE.defaultDurationMs)
        Log.d(TAG, "CUDDLE message shown; edge=${if (catOnLeft) "left" else "right"}")
    }

    /** Shows relationship dialogue without replacing the behavior animation currently playing. */
    fun showPassiveMessage(message: String) {
        activeReminderConfirmation = null
        activeReminderType = null
        cancelPendingStateTransition()
        showBubble(message)
    }

    fun showReminderState(
        reminderType: ReminderType,
        state: CatState,
        message: String,
        confirmationMessage: String,
        targetState: CatState = CatState.IDLE,
        delayMillis: Long = state.defaultDurationMs
    ) {
        activeReminderConfirmation = confirmationMessage
        activeReminderType = reminderType
        setCatState(state)
        showBubble(message)
        if (delayMillis > 0L) {
            scheduleStateTransition(state, targetState, delayMillis)
        }
    }

    fun release() {
        cancelPendingStateTransition()
        handler.removeCallbacksAndMessages(BUBBLE_TOKEN)
        stateManager.removeListener(stateListener)
        animationManager.release()
        activeReminderConfirmation = null
        activeReminderType = null
    }

    private fun showBubble(message: String, durationMs: Long = 3_000L) {
        handler.removeCallbacksAndMessages(BUBBLE_TOKEN)
        bubbleView.showMessage(message)
        handler.postAtTime({ bubbleView.hide() }, BUBBLE_TOKEN, SystemClock.uptimeMillis() + durationMs)
    }

    private fun setCatState(state: CatState) {
        cancelPendingStateTransition()
        stateManager.switchTo(state)
    }

    private fun scheduleStateTransition(
        expectedCurrentState: CatState,
        targetState: CatState,
        delayMillis: Long
    ) {
        cancelPendingStateTransition()
        val transition = Runnable {
            pendingStateRunnable = null
            if (stateManager.currentState != expectedCurrentState || dragging) {
                Log.d(TAG, "stale state transition ignored: expected=$expectedCurrentState, actual=${stateManager.currentState}")
                return@Runnable
            }
            stateManager.switchTo(targetState)
            if (targetState == CatState.IDLE) {
                activeReminderConfirmation = null
                activeReminderType = null
            }
        }
        pendingStateRunnable = transition
        handler.postDelayed(transition, delayMillis)
    }

    private fun cancelPendingStateTransition() {
        pendingStateRunnable?.let(handler::removeCallbacks)
        pendingStateRunnable = null
    }

    private fun installTouch() {
        catImage.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val totalDistance = hypot(
                        (event.rawX - downRawX).toDouble(),
                        (event.rawY - downRawY).toDouble()
                    )
                    if (!dragging && totalDistance > touchSlopPx) {
                        dragging = true
                        callbacks.onUserInteractionStarted(UserInteraction.DRAG)
                        activeReminderConfirmation = null
                        activeReminderType = null
                        setCatState(CatState.DRAGGING)
                    }
                    if (dragging) {
                        callbacks.onDragBy(
                            (event.rawX - lastRawX).toInt(),
                            (event.rawY - lastRawY).toInt()
                        )
                    }
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downRawX) > touchSlopPx ||
                        abs(event.rawY - downRawY) > touchSlopPx
                    if (dragging || moved) {
                        callbacks.onDragEnd()
                        setCatState(CatState.IDLE)
                    } else {
                        catImage.performClick()
                    }
                    dragging = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        callbacks.onDragEnd()
                        setCatState(CatState.IDLE)
                    }
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        handlePetClick()
        return true
    }

    private fun handlePetClick() {
        if (stateManager.currentState == CatState.DRAGGING) return
        callbacks.onUserInteractionStarted(UserInteraction.CLICK)
        val confirmation = activeReminderConfirmation
        if (confirmation != null) {
            activeReminderType?.let { type ->
                callbacks.onReminderConfirmed(type, stateManager.currentState)
            }
            activeReminderConfirmation = null
            activeReminderType = null
            Log.d(TAG, "reminder confirmed while state=${stateManager.currentState}")
            setCatState(CatState.HAPPY)
            showBubble(confirmation)
            scheduleStateTransition(CatState.HAPPY, CatState.IDLE, CatState.HAPPY.defaultDurationMs)
        } else {
            callbacks.onClickPet()
        }
    }

    private companion object {
        const val TAG = "CatLifePet"
        private val BUBBLE_TOKEN = Any()

        private object NoOpCallbacks : Callbacks {
            override fun onDragBy(dx: Int, dy: Int) = Unit
            override fun onDragEnd() = Unit
            override fun onClickPet() = Unit
            override fun onUserInteractionStarted(kind: UserInteraction) = Unit
            override fun onReminderConfirmed(type: ReminderType, stateAtConfirmation: CatState) = Unit
        }
    }

    private inner class PetImageView(context: Context) : ImageView(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return this@CatPetView.performClick()
        }
    }
}
