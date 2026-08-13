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

    @Test
    fun `privacy policy keeps release placeholders explicit`() {
        val privacyPolicy = File("../PRIVACY_POLICY.md").readText()

        assertTrue(privacyPolicy.contains("{运营者名称}"))
        assertTrue(privacyPolicy.contains("{隐私联系邮箱}"))
        assertTrue(privacyPolicy.contains("{服务器所在国家或地区}"))
        assertTrue(privacyPolicy.contains("{隐私政策 URL}"))
        assertTrue(privacyPolicy.contains("DeepSeek"))
    }
}
