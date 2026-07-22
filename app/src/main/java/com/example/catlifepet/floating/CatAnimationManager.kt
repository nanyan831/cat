package com.example.catlifepet.floating

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.res.ResourcesCompat
import com.example.catlifepet.R

/** Plays state visuals while leaving state lifetime decisions to CatStateManager. */
class CatAnimationManager(private val imageView: ImageView) {
    private val handler = Handler(Looper.getMainLooper())
    private var playingState: CatState? = null
    private var currentFrameIndex = 0
    private var frameLoopRunning = false
    private var loadedState: CatState? = null
    private var loadedFrames: List<LoadedFrame> = emptyList()

    @Volatile
    private var released = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (released || !frameLoopRunning) return
            val state = playingState ?: return
            val animation = stateAnimations[state] ?: return
            val frames = loadedFrames
            if (frames.isEmpty()) {
                showStaticFallback(state)
                frameLoopRunning = false
                return
            }

            val nextIndex = currentFrameIndex + 1
            if (nextIndex >= frames.size) {
                if (animation.playbackMode == PlaybackMode.ONCE) {
                    frameLoopRunning = false
                    Log.d(TAG, "animation finished and holding last frame: state=$state")
                    return
                }
                currentFrameIndex = 0
            } else {
                currentFrameIndex = nextIndex
            }
            showFrame(state, frames[currentFrameIndex])
            handler.postDelayed(this, frames[currentFrameIndex].durationMs)
        }
    }

    private val stateAnimations: Map<CatState, StateAnimation> = mapOf(
        CatState.IDLE to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_idle_01, 180L),
                AnimationFrame(R.drawable.cat_idle_02, 160L),
                AnimationFrame(R.drawable.cat_idle_03, 180L),
                AnimationFrame(R.drawable.cat_idle_04, 160L),
                AnimationFrame(R.drawable.cat_idle_05, 180L),
                AnimationFrame(R.drawable.cat_idle_06, 180L)
            ),
            playbackMode = PlaybackMode.LOOP
        ),
        CatState.HAPPY to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_happy_01, 120L),
                AnimationFrame(R.drawable.cat_happy_02, 120L),
                AnimationFrame(R.drawable.cat_happy_03, 140L),
                AnimationFrame(R.drawable.cat_happy_04, 160L),
                AnimationFrame(R.drawable.cat_happy_05, 140L),
                AnimationFrame(R.drawable.cat_happy_06, 200L)
            ),
            playbackMode = PlaybackMode.ONCE
        ),
        CatState.EATING to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_eating_01, 140L),
                AnimationFrame(R.drawable.cat_eating_02, 120L),
                AnimationFrame(R.drawable.cat_eating_03, 140L),
                AnimationFrame(R.drawable.cat_eating_04, 120L),
                AnimationFrame(R.drawable.cat_eating_05, 140L),
                AnimationFrame(R.drawable.cat_eating_06, 120L),
                AnimationFrame(R.drawable.cat_eating_07, 140L),
                AnimationFrame(R.drawable.cat_eating_08, 220L)
            ),
            playbackMode = PlaybackMode.LOOP
        ),
        CatState.DRINKING to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_drinking_01, 160L),
                AnimationFrame(R.drawable.cat_drinking_02, 140L),
                AnimationFrame(R.drawable.cat_drinking_03, 140L),
                AnimationFrame(R.drawable.cat_drinking_04, 180L),
                AnimationFrame(R.drawable.cat_drinking_05, 160L),
                AnimationFrame(R.drawable.cat_drinking_06, 140L),
                AnimationFrame(R.drawable.cat_drinking_07, 160L),
                AnimationFrame(R.drawable.cat_drinking_08, 240L)
            ),
            playbackMode = PlaybackMode.LOOP
        ),
        CatState.SLEEPING to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_sleeping_01, 350L),
                AnimationFrame(R.drawable.cat_sleeping_02, 350L),
                AnimationFrame(R.drawable.cat_sleeping_03, 450L),
                AnimationFrame(R.drawable.cat_sleeping_04, 350L),
                AnimationFrame(R.drawable.cat_sleeping_05, 350L),
                AnimationFrame(R.drawable.cat_sleeping_06, 500L)
            ),
            playbackMode = PlaybackMode.LOOP
        ),
        CatState.STRETCHING to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_stretching_01, 180L),
                AnimationFrame(R.drawable.cat_stretching_02, 180L),
                AnimationFrame(R.drawable.cat_stretching_03, 220L),
                AnimationFrame(R.drawable.cat_stretching_04, 250L),
                AnimationFrame(R.drawable.cat_stretching_05, 350L),
                AnimationFrame(R.drawable.cat_stretching_06, 300L)
            ),
            playbackMode = PlaybackMode.ONCE
        ),
        CatState.DRAGGING to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_dragging_01, 180L),
                AnimationFrame(R.drawable.cat_dragging_02, 180L),
                AnimationFrame(R.drawable.cat_dragging_03, 180L),
                AnimationFrame(R.drawable.cat_dragging_04, 180L)
            ),
            playbackMode = PlaybackMode.LOOP
        ),
        CatState.BLINKING to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_blinking_01, 80L),
                AnimationFrame(R.drawable.cat_blinking_02, 70L),
                AnimationFrame(R.drawable.cat_blinking_03, 90L),
                AnimationFrame(R.drawable.cat_blinking_04, 70L),
                AnimationFrame(R.drawable.cat_blinking_05, 100L)
            ),
            playbackMode = PlaybackMode.ONCE
        ),
        CatState.YAWNING to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_yawning_01, 180L),
                AnimationFrame(R.drawable.cat_yawning_02, 180L),
                AnimationFrame(R.drawable.cat_yawning_03, 200L),
                AnimationFrame(R.drawable.cat_yawning_04, 230L),
                AnimationFrame(R.drawable.cat_yawning_05, 300L),
                AnimationFrame(R.drawable.cat_yawning_06, 240L),
                AnimationFrame(R.drawable.cat_yawning_07, 180L),
                AnimationFrame(R.drawable.cat_yawning_08, 180L)
            ),
            playbackMode = PlaybackMode.ONCE
        ),
        CatState.CURIOUS to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_curious_01, 180L),
                AnimationFrame(R.drawable.cat_curious_02, 160L),
                AnimationFrame(R.drawable.cat_curious_03, 180L),
                AnimationFrame(R.drawable.cat_curious_04, 200L),
                AnimationFrame(R.drawable.cat_curious_05, 320L),
                AnimationFrame(R.drawable.cat_curious_06, 220L),
                AnimationFrame(R.drawable.cat_curious_07, 180L),
                AnimationFrame(R.drawable.cat_curious_08, 180L)
            ),
            playbackMode = PlaybackMode.ONCE
        ),
        CatState.CUDDLE to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_cuddle_01, 180L),
                AnimationFrame(R.drawable.cat_cuddle_02, 170L),
                AnimationFrame(R.drawable.cat_cuddle_03, 190L),
                AnimationFrame(R.drawable.cat_cuddle_04, 220L),
                AnimationFrame(R.drawable.cat_cuddle_05, 330L),
                AnimationFrame(R.drawable.cat_cuddle_06, 230L),
                AnimationFrame(R.drawable.cat_cuddle_07, 180L),
                AnimationFrame(R.drawable.cat_cuddle_08, 180L)
            ),
            playbackMode = PlaybackMode.ONCE
        ),
        CatState.LICKING to StateAnimation(
            frames = listOf(
                AnimationFrame(R.drawable.cat_licking_01, 160L),
                AnimationFrame(R.drawable.cat_licking_02, 150L),
                AnimationFrame(R.drawable.cat_licking_03, 180L),
                AnimationFrame(R.drawable.cat_licking_04, 180L),
                AnimationFrame(R.drawable.cat_licking_05, 260L),
                AnimationFrame(R.drawable.cat_licking_06, 260L),
                AnimationFrame(R.drawable.cat_licking_07, 180L),
                AnimationFrame(R.drawable.cat_licking_08, 170L),
                AnimationFrame(R.drawable.cat_licking_09, 160L),
                AnimationFrame(R.drawable.cat_licking_10, 180L)
            ),
            playbackMode = PlaybackMode.ONCE
        )
    )

    fun play(state: CatState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { play(state) }
            return
        }
        if (released) return

        val animation = stateAnimations[state]
        val sameState = playingState == state
        if (sameState && animation?.playbackMode == PlaybackMode.LOOP && frameLoopRunning) {
            return
        }

        stopFrameLoop()
        playingState = state
        currentFrameIndex = 0
        loadedFrames = animation?.let { loadFrames(state, it) }.orEmpty()

        if (loadedFrames.isEmpty()) {
            showStaticFallback(state)
            Log.w(TAG, "animation unavailable; using static fallback: state=$state")
        } else {
            frameLoopRunning = true
            showFrame(state, loadedFrames.first())
            handler.postDelayed(frameRunnable, loadedFrames.first().durationMs)
        }
        Log.d(TAG, "cat visual state: $state, mode=${animation?.playbackMode ?: "STATIC"}")
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stop() }
            return
        }
        stopFrameLoop()
        playingState = null
        loadedState = null
        loadedFrames = emptyList()
    }

    @DrawableRes
    fun getDrawableForState(state: CatState): Int {
        return when (state) {
            CatState.IDLE -> R.drawable.cat_idle
            CatState.HAPPY -> R.drawable.cat_happy
            CatState.EATING -> R.drawable.cat_eating
            CatState.DRINKING -> R.drawable.cat_drinking
            CatState.SLEEPING -> R.drawable.cat_sleeping
            CatState.STRETCHING -> R.drawable.cat_stretching
            CatState.DRAGGING -> R.drawable.cat_dragging
            CatState.BLINKING -> R.drawable.cat_idle
            CatState.YAWNING -> R.drawable.cat_stretching
            CatState.CURIOUS -> R.drawable.cat_idle
            CatState.CUDDLE -> R.drawable.cat_idle
            CatState.LICKING -> R.drawable.cat_idle
        }
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        frameLoopRunning = false
        currentFrameIndex = 0
        playingState = null
        loadedState = null
        loadedFrames = emptyList()
        Log.d(TAG, "cat animation manager released")
    }

    private fun loadFrames(state: CatState, animation: StateAnimation): List<LoadedFrame> {
        if (loadedState == state && loadedFrames.size == animation.frames.size) {
            return loadedFrames
        }
        val frames = animation.frames.mapNotNull { frame ->
            runCatching {
                ResourcesCompat.getDrawable(
                    imageView.resources,
                    frame.drawableRes,
                    imageView.context.theme
                )?.let { LoadedFrame(it, frame.durationMs) }
            }.onFailure { error ->
                Log.w(TAG, "failed to load animation frame: ${frame.drawableRes}", error)
            }.getOrNull()
        }
        loadedState = state
        return if (frames.size == animation.frames.size) frames else emptyList()
    }

    private fun stopFrameLoop() {
        val wasRunning = frameLoopRunning
        handler.removeCallbacks(frameRunnable)
        frameLoopRunning = false
        currentFrameIndex = 0
        if (wasRunning) Log.d(TAG, "animation stopped: state=$playingState")
    }

    private fun showFrame(state: CatState, frame: LoadedFrame) {
        imageView.setImageDrawable(frame.drawable)
        Log.v(TAG, "animation frame displayed: state=$state, index=${currentFrameIndex + 1}")
    }

    private fun showStaticFallback(state: CatState) {
        imageView.setImageResource(getDrawableForState(state))
    }

    private data class LoadedFrame(
        val drawable: Drawable,
        val durationMs: Long
    )

    data class AnimationFrame(
        @DrawableRes val drawableRes: Int,
        val durationMs: Long
    )

    data class StateAnimation(
        val frames: List<AnimationFrame>,
        val playbackMode: PlaybackMode
    )

    enum class PlaybackMode {
        LOOP,
        ONCE
    }

    private companion object {
        const val TAG = "CatLifePet"
    }
}
