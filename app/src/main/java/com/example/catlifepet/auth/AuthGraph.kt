package com.example.catlifepet.auth

import android.content.Context
import android.os.Build
import com.example.catlifepet.BuildConfig
import com.google.gson.Gson

object AuthGraph {
    @Volatile
    private var repository: AuthRepository? = null

    fun repository(context: Context): AuthRepository {
        return repository ?: synchronized(this) {
            repository ?: createRepository(context.applicationContext).also { repository = it }
        }
    }

    private fun createRepository(context: Context): AuthRepository {
        val gson = Gson()
        val tokenProvider = AccessTokenProvider()
        return AuthRepository(
            api = AuthApiFactory.create(BuildConfig.API_BASE_URL, tokenProvider, gson),
            sessionStore = AndroidKeystoreSessionStore(context),
            tokenProvider = tokenProvider,
            deviceLabel = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter(String::isNotBlank)
                .joinToString(" ")
                .take(160),
            gson = gson
        )
    }
}
