package com.example.catlifepet.auth

import android.content.Context
import android.os.Build
import com.example.catlifepet.BuildConfig
import com.google.gson.Gson
import okhttp3.OkHttpClient

object AuthGraph {
    @Volatile
    private var dependencies: Dependencies? = null

    fun repository(context: Context): AuthRepository {
        return dependencies(context).repository
    }

    internal fun httpClient(context: Context): OkHttpClient = dependencies(context).httpClient
    internal fun gson(context: Context): Gson = dependencies(context).gson

    private fun dependencies(context: Context): Dependencies {
        return dependencies ?: synchronized(this) {
            dependencies ?: createDependencies(context.applicationContext).also { dependencies = it }
        }
    }

    private fun createDependencies(context: Context): Dependencies {
        val gson = Gson()
        val tokenProvider = AccessTokenProvider()
        val httpClient = AuthApiFactory.createClient(tokenProvider)
        val repository = AuthRepository(
            api = AuthApiFactory.create(BuildConfig.API_BASE_URL, tokenProvider, gson, httpClient),
            sessionStore = AndroidKeystoreSessionStore(context),
            tokenProvider = tokenProvider,
            deviceLabel = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter(String::isNotBlank)
                .joinToString(" ")
                .take(160),
            gson = gson
        )
        return Dependencies(repository, httpClient, gson)
    }

    private data class Dependencies(
        val repository: AuthRepository,
        val httpClient: OkHttpClient,
        val gson: Gson
    )
}
