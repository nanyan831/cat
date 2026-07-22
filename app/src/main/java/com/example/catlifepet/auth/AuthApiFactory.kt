package com.example.catlifepet.auth

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AccessTokenProvider {
    @Volatile
    var accessToken: String? = null
}

class AccessTokenInterceptor(
    private val tokenProvider: AccessTokenProvider
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = tokenProvider.accessToken
        val path = chain.request().url.encodedPath
        val request = if (token.isNullOrBlank() || PUBLIC_AUTH_PATHS.any(path::startsWith)) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }

    private companion object {
        val PUBLIC_AUTH_PATHS = listOf(
            "/v1/auth/code/request",
            "/v1/auth/code/verify",
            "/v1/auth/refresh"
        )
    }
}

object AuthApiFactory {
    fun create(
        baseUrl: String,
        tokenProvider: AccessTokenProvider,
        gson: Gson = Gson(),
        client: OkHttpClient? = null
    ): AuthApi {
        val httpClient = client ?: OkHttpClient.Builder()
            .addInterceptor(AccessTokenInterceptor(tokenProvider))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(AuthApi::class.java)
    }
}
