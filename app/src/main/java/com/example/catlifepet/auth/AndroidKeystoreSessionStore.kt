package com.example.catlifepet.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreSessionStore(context: Context) : SessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun readRefreshToken(): String? = synchronized(lock) {
        val encoded = preferences.getString(KEY_REFRESH_TOKEN, null) ?: return@synchronized null
        runCatching {
            val parts = encoded.split(':', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(ASSOCIATED_DATA)
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse {
            preferences.edit().remove(KEY_REFRESH_TOKEN).apply()
            null
        }
    }

    override fun writeRefreshToken(refreshToken: String) = synchronized(lock) {
        require(refreshToken.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            updateAAD(ASSOCIATED_DATA)
        }
        val encoded = listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        ).joinToString(":")
        preferences.edit().putString(KEY_REFRESH_TOKEN, encoded).apply()
    }

    override fun clear() = synchronized(lock) {
        preferences.edit().remove(KEY_REFRESH_TOKEN).apply()
        Unit
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "catlifepet_secure_session"
        const val KEY_REFRESH_TOKEN = "refresh_token_ciphertext"
        const val KEY_ALIAS = "catlifepet_refresh_token_key_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val ASSOCIATED_DATA = "CatLifePetRefreshTokenV1".toByteArray(Charsets.UTF_8)
    }
}
