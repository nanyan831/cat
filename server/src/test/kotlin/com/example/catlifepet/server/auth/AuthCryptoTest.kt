package com.example.catlifepet.server.auth

import org.junit.jupiter.api.Test
import java.security.SecureRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuthCryptoTest {
    private val crypto = AuthCrypto(
        pepper = "test-only-pepper-with-at-least-32-characters",
        secureRandom = SecureRandom()
    )

    @Test
    fun `verification hashes are deterministic scoped and never plaintext`() {
        val first = crypto.hashVerificationCode("cat@example.com", "123456")
        val second = crypto.hashVerificationCode("cat@example.com", "123456")
        val refreshHash = crypto.hashRefreshToken("cat@example.com:123456")

        assertContentEquals(first, second)
        assertFalse(first.contentEquals(refreshHash))
        assertFalse(first.contentEquals("123456".toByteArray()))
    }

    @Test
    fun `generated credentials use fixed safe formats`() {
        assertEquals(6, crypto.generateVerificationCode().length)
        assertFalse(crypto.generateVerificationCode().any { !it.isDigit() })
        assertEquals(43, crypto.generateRefreshToken().length)
    }
}
