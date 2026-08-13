package com.example.catlifepet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseSurfaceTest {
    @Test
    fun `main manifest does not expose debug surfaces`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains(".debug."))
        assertFalse(manifest.contains("OverlayDebugActivity"))
        assertTrue(manifest.contains("""android:name=".floating.CatFloatingService""""))
        assertTrue(manifest.contains("""android:exported="false""""))
    }
}
