package com.example.catlifepet.floating

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.catlifepet.data.SettingsRepository
import com.example.catlifepet.permission.OverlayPermissionHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayServiceLifecycleInstrumentedTest {
    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(OverlayPermissionHelper.canDrawOverlays(context))
        settings = SettingsRepository(context)
        settings.clearTemporaryHide()
        context.stopService(Intent(context, CatFloatingService::class.java))
        waitFor { OverlayLifecycleDiagnostics.activeViewCount() == 0 }
        OverlayLifecycleDiagnostics.resetForTest()
    }

    @After
    fun tearDown() {
        if (::settings.isInitialized) {
            settings.clearTemporaryHide()
        }
        if (::context.isInitialized) {
            CatFloatingService.stop(context)
            waitFor { OverlayLifecycleDiagnostics.activeViewCount() == 0 }
        }
    }

    @Test
    fun repeatedStartAndRestartNeverAddsDuplicateAndPersistedHideRestores() {
        repeat(10) { CatFloatingService.start(context) }
        waitFor { OverlayLifecycleDiagnostics.activeViewCount() == 1 }
        assertEquals(1, OverlayLifecycleDiagnostics.maximumViewCount())

        val hiddenUntil = System.currentTimeMillis() + 1_500L
        settings.saveTemporaryHideUntil(hiddenUntil)
        context.stopService(Intent(context, CatFloatingService::class.java))
        waitFor { OverlayLifecycleDiagnostics.activeViewCount() == 0 }

        CatFloatingService.start(context)
        Thread.sleep(350)
        assertEquals(0, OverlayLifecycleDiagnostics.activeViewCount())
        assertEquals(hiddenUntil, settings.getTemporaryHideUntil())
        waitFor(timeoutMillis = 4_000L) { OverlayLifecycleDiagnostics.activeViewCount() == 1 }
        assertEquals(0L, settings.getTemporaryHideUntil())
        assertEquals(1, OverlayLifecycleDiagnostics.maximumViewCount())

        repeat(5) {
            context.stopService(Intent(context, CatFloatingService::class.java))
            waitFor { OverlayLifecycleDiagnostics.activeViewCount() == 0 }
            CatFloatingService.start(context)
            waitFor { OverlayLifecycleDiagnostics.activeViewCount() == 1 }
        }
        assertEquals(1, OverlayLifecycleDiagnostics.maximumViewCount())
    }

    private fun waitFor(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("Timed out waiting for overlay lifecycle state", false)
    }
}
