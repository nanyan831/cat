package com.example.catlifepet.auth

data class RequestLoginCodeRequest(val email: String)

data class RequestLoginCodeResponse(
    val accepted: Boolean,
    val expiresInSeconds: Long
)

data class VerifyLoginCodeRequest(
    val email: String,
    val code: String,
    val deviceLabel: String? = null
)

data class RefreshSessionRequest(
    val refreshToken: String,
    val deviceLabel: String? = null
)

data class AuthSessionResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresInSeconds: Long,
    val user: UserProfile
)

data class UserProfile(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val timeZone: String
)

data class UpdateProfileRequest(
    val displayName: String? = null,
    val timeZone: String? = null
)

data class ActionResponse(val success: Boolean)

data class ApiErrorEnvelope(
    val error: ApiError,
    val requestId: String? = null
)

data class ApiError(
    val code: String,
    val message: String
)
