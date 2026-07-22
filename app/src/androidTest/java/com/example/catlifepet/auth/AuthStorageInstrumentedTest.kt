package com.example.catlifepet.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthStorageInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: AndroidKeystoreSessionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AndroidKeystoreSessionStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun refreshTokenRoundTripsEncryptedAndCanBeCleared() {
        val refreshToken = "instrumentation-refresh-token-sensitive-value"

        store.writeRefreshToken(refreshToken)

        assertEquals(refreshToken, store.readRefreshToken())
        val rawPreferences = context
            .getSharedPreferences("catlifepet_secure_session", Context.MODE_PRIVATE)
            .all
            .values
            .joinToString()
        assertFalse(rawPreferences.contains(refreshToken))

        store.clear()
        assertNull(store.readRefreshToken())
    }
}
