package com.example.catlifepet.floating

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.catlifepet.permission.OverlayPermissionHelper
import com.example.catlifepet.reminder.ReminderType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceEnduranceInstrumentedTest {
    @Test
    fun oneHundredOverlayActionsDoNotDuplicatePetWindow() = runBlocking {
        assumeTrue(
            "Run only with -e enduranceSmoke true",
            InstrumentationRegistry.getArguments().getString("enduranceSmoke") == "true"
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Overlay permission must be granted before this test", OverlayPermissionHelper.canDrawOverlays(context))

        OverlayLifecycleDiagnostics.resetForTest()
        CatFloatingService.start(context)
        waitUntil("pet view added") { OverlayLifecycleDiagnostics.activeViewCount() == 1 }

        val actions: List<() -> Unit> = listOf(
            { CatFloatingService.showRandomTalk(context) },
            { CatFloatingService.showReminder(context, ReminderType.WATER) },
            { CatFloatingService.showReminder(context, ReminderType.FOOD) },
            { CatFloatingService.showReminder(context, ReminderType.REST) },
            { CatFloatingService.showReminder(context, ReminderType.SLEEP) },
            { CatFloatingService.showDebugBehavior(context, CatState.BLINKING) },
            { CatFloatingService.showDebugBehavior(context, CatState.YAWNING) },
            { CatFloatingService.showDebugBehavior(context, CatState.LICKING) },
            { CatFloatingService.showDebugRelationshipBehavior(context, CatFloatingService.ACTION_DEBUG_CURIOUS) },
            { CatFloatingService.showDebugRelationshipBehavior(context, CatFloatingService.ACTION_DEBUG_CUDDLE) }
        )

        repeat(100) { index ->
            actions[index % actions.size].invoke()
            delay(45L)
            assertTrue(
                "duplicate pet windows after action $index",
                OverlayLifecycleDiagnostics.maximumViewCount() <= 1
            )
        }

        CatFloatingService.stop(context)
        waitUntil("pet view removed") { OverlayLifecycleDiagnostics.activeViewCount() == 0 }
        assertEquals(0, OverlayLifecycleDiagnostics.activeViewCount())
        assertTrue(OverlayLifecycleDiagnostics.maximumViewCount() <= 1)
    }

    private suspend fun waitUntil(label: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 12_000L
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(100L)
        }
        error("Timed out waiting for $label")
    }
}
