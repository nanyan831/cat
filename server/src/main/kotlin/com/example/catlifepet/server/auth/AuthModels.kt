package com.example.catlifepet.server.auth

import kotlinx.serialization.Serializable

@Serializable
data class RequestLoginCodeRequest(val email: String)

@Serializable
data class RequestLoginCodeResponse(
    val accepted: Boolean = true,
    val expiresInSeconds: Long
)

@Serializable
data class VerifyLoginCodeRequest(
    val email: String,
    val code: String,
    val deviceLabel: String? = null
)

@Serializable
data class RefreshSessionRequest(
    val refreshToken: String,
    val deviceLabel: String? = null
)

@Serializable
data class AuthSessionResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long,
    val user: UserResponse
)

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val timeZone: String
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val timeZone: String? = null
)

@Serializable
data class ActionResponse(val success: Boolean = true)
