package com.example.catlifepet.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class AuthCrypto(
    pepper: String,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private val key = SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)

    fun generateVerificationCode(): String = secureRandom.nextInt(1_000_000).toString().padStart(6, '0')

    fun generateRefreshToken(): String {
        val bytes = ByteArray(32).also(secureRandom::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hashVerificationCode(normalizedEmail: String, code: String): ByteArray {
        return hash("verification-code", "$normalizedEmail:$code")
    }

    fun hashRefreshToken(token: String): ByteArray = hash("refresh-token", token)

    fun hashIpAddress(ipAddress: String): ByteArray = hash("request-ip", ipAddress)

    fun matches(expected: ByteArray, actual: ByteArray): Boolean = MessageDigest.isEqual(expected, actual)

    private fun hash(namespace: String, value: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        return mac.doFinal("$namespace\u0000$value".toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
