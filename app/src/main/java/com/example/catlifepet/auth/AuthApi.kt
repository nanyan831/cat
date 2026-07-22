package com.example.catlifepet.auth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST

interface AuthApi {
    @POST("v1/auth/code/request")
    suspend fun requestCode(@Body request: RequestLoginCodeRequest): Response<RequestLoginCodeResponse>

    @POST("v1/auth/code/verify")
    suspend fun verifyCode(@Body request: VerifyLoginCodeRequest): Response<AuthSessionResponse>

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshSessionRequest): Response<AuthSessionResponse>

    @POST("v1/auth/logout")
    suspend fun logout(): Response<ActionResponse>

    @GET("v1/me")
    suspend fun getMe(): Response<UserProfile>

    @PATCH("v1/me")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<UserProfile>

    @DELETE("v1/me")
    suspend fun deleteAccount(): Response<ActionResponse>
}
