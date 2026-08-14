package com.example.catlifepet.util

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

object InteractionUtils {
    fun View.applySpringPressEffect(
        pressedScale: Float = 0.96f,
        dampingRatio: Float = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY,
        stiffness: Float = SpringForce.STIFFNESS_MEDIUM
    ): View {
        val springX = spring(DynamicAnimation.SCALE_X, dampingRatio, stiffness)
        val springY = spring(DynamicAnimation.SCALE_Y, dampingRatio, stiffness)
        @SuppressLint("ClickableViewAccessibility")
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    springX.animateToFinalPosition(pressedScale)
                    springY.animateToFinalPosition(pressedScale)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    springX.animateToFinalPosition(1f)
                    springY.animateToFinalPosition(1f)
                }
            }
            false
        }
        return this
    }

    fun View.fadeSlideIn(
        offsetYPx: Float,
        durationMs: Long = 220L
    ) {
        alpha = 0f
        translationY = offsetYPx
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .start()
    }

    private fun View.spring(
        property: DynamicAnimation.ViewProperty,
        dampingRatio: Float,
        stiffness: Float
    ): SpringAnimation = SpringAnimation(this, property).apply {
        spring = SpringForce(1f).apply {
            this.dampingRatio = dampingRatio
            this.stiffness = stiffness
        }
    }
}
