package com.example.catlifepet.floating

import java.util.concurrent.atomic.AtomicInteger

object OverlayLifecycleDiagnostics {
    private val activeViews = AtomicInteger()
    private val maximumViews = AtomicInteger()

    fun onViewAdded() {
        val active = activeViews.incrementAndGet()
        maximumViews.updateAndGet { previous -> maxOf(previous, active) }
    }

    fun onViewRemoved() {
        activeViews.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
    }

    fun activeViewCount(): Int = activeViews.get()
    fun maximumViewCount(): Int = maximumViews.get()

    fun resetForTest() {
        activeViews.set(0)
        maximumViews.set(0)
    }
}
