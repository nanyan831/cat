package com.example.catlifepet.memory

import android.content.Context
import com.example.catlifepet.BuildConfig
import com.example.catlifepet.auth.AuthGraph

object MemoryGraph {
    @Volatile private var source: MemoryDataSource? = null
    @Volatile internal var testDataSource: MemoryDataSource? = null

    fun source(context: Context): MemoryDataSource {
        testDataSource?.let { return it }
        return source ?: synchronized(this) {
            source ?: MemoryRepository(
                BuildConfig.API_BASE_URL,
                AuthGraph.httpClient(context),
                AuthGraph.gson(context),
                AuthGraph.repository(context)
            ).also { source = it }
        }
    }

    internal fun clearTestDataSource() {
        testDataSource = null
    }
}
