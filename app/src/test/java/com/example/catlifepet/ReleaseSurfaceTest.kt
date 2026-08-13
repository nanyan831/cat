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

    @Test
    fun `activities use shared system bar helper instead of legacy light status flag`() {
        val sourceRoot = File("src/main/java/com/example/catlifepet")
        val legacyUses = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SystemBarUtils.kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("SYSTEM_UI_FLAG_LIGHT_STATUS_BAR") || text.contains("systemUiVisibility")
            }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toList()

        assertTrue("Legacy system bar flags found in $legacyUses", legacyUses.isEmpty())
    }

    @Test
    fun `release network security disables cleartext traffic`() {
        val manifest = File("src/release/AndroidManifest.xml").readText()
        val releaseConfig = File("src/release/res/xml/network_security_config.xml").readText()
        val debugConfig = File("src/debug/res/xml/network_security_config.xml").readText()

        assertTrue(manifest.contains("""android:usesCleartextTraffic="false""""))
        assertTrue(manifest.contains("""android:networkSecurityConfig="@xml/network_security_config""""))
        assertTrue(releaseConfig.contains("""cleartextTrafficPermitted="false""""))
        assertFalse(releaseConfig.contains("127.0.0.1"))
        assertFalse(releaseConfig.contains("47.100.9.190"))
        assertTrue(debugConfig.contains("""cleartextTrafficPermitted="true""""))
        assertTrue(debugConfig.contains("47.100.9.190"))
    }
}
