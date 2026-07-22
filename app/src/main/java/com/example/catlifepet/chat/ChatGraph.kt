package com.example.catlifepet.chat

import android.content.Context
import com.example.catlifepet.BuildConfig
import com.example.catlifepet.auth.AuthGraph
import com.example.catlifepet.chat.data.CatLifePetDatabase
import com.example.catlifepet.chat.network.ChatHttpClient

object ChatGraph {
    @Volatile private var repository: ChatDataSource? = null
    @Volatile internal var testDataSource: ChatDataSource? = null

    fun repository(context: Context): ChatDataSource {
        testDataSource?.let { return it }
        return repository ?: synchronized(this) {
            repository ?: ChatRepository(
                authRepository = AuthGraph.repository(context),
                http = ChatHttpClient(
                    BuildConfig.API_BASE_URL,
                    AuthGraph.httpClient(context),
                    AuthGraph.gson(context)
                ),
                dao = CatLifePetDatabase.getInstance(context).chatDao()
            ).also { repository = it }
        }
    }

    internal fun clearTestDataSource() { testDataSource = null }
}
