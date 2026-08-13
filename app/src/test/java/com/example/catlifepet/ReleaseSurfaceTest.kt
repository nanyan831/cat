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
    fun `debug overlay screen is not exported outside the test build`() {
        val debugManifest = File("src/debug/AndroidManifest.xml").readText()

        assertTrue(debugManifest.contains("OverlayDebugActivity"))
        assertTrue(debugManifest.contains("""android:exported="false""""))
    }

    @Test
    fun `privacy policy has publishable operator and contact details`() {
        val privacyPolicy = File("../PRIVACY_POLICY.md").readText()
        val unresolvedPlaceholders = Regex("""\{[^}]+}""")
            .findAll(privacyPolicy)
            .map { it.value }
            .toList()

        assertTrue("Unresolved privacy placeholders found: $unresolvedPlaceholders", unresolvedPlaceholders.isEmpty())
        assertTrue(privacyPolicy.contains("CatLifePet 项目组"))
        assertTrue(privacyPolicy.contains("1132994878@qq.com"))
        assertTrue(privacyPolicy.contains("中国大陆（阿里云华东 2 上海）"))
        assertTrue(privacyPolicy.contains("https://catlifepet.top/privacy"))
        assertTrue(privacyPolicy.contains("DeepSeek"))
    }

    @Test
    fun `release checklist does not keep unresolved publication placeholders`() {
        val checklist = File("../RELEASE_CHECKLIST.md").readText()
        val unresolvedPlaceholders = Regex("""\{[^}]+}""")
            .findAll(checklist)
            .map { it.value }
            .toList()

        assertTrue("Unresolved release checklist placeholders found: $unresolvedPlaceholders", unresolvedPlaceholders.isEmpty())
        assertTrue(checklist.contains("https://catlifepet.top/privacy"))
        assertTrue(checklist.contains("不得包含"))
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

    @Test
    fun `manifest disables system backup for local tokens and chat cache`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val backupRules = File("src/main/res/xml/backup_rules.xml").readText()
        val dataExtractionRules = File("src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        listOf("sharedpref", "database", "file", "external", "root").forEach { domain ->
            assertTrue(backupRules.contains("""domain="$domain""""))
            assertTrue(dataExtractionRules.contains("""domain="$domain""""))
        }
        assertTrue(dataExtractionRules.contains("<cloud-backup>"))
        assertTrue(dataExtractionRules.contains("<device-transfer>"))
    }

    @Test
    fun `privacy chat records entry opens conversation list`() {
        val source = File("src/main/java/com/example/catlifepet/privacy/PrivacyActivity.kt").readText()

        assertTrue(source.contains("ChatConversationListActivity::class.java"))
        assertFalse(source.contains("ChatActivity::class.java"))
    }

    @Test
    fun `primary screens share spring press interaction feedback`() {
        listOf(
            "src/main/java/com/example/catlifepet/MainActivity.kt",
            "src/main/java/com/example/catlifepet/chat/ChatActivity.kt",
            "src/main/java/com/example/catlifepet/chat/ChatConversationListActivity.kt",
            "src/main/java/com/example/catlifepet/memory/MemoryActivity.kt",
            "src/main/java/com/example/catlifepet/privacy/PrivacyActivity.kt"
        ).forEach { path ->
            val source = File(path).readText()
            assertTrue("$path should use shared spring feedback", source.contains("applySpringPressEffect"))
        }
    }
}
