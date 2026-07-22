package com.example.catlifepet.server.http

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val version: String,
    val environment: String,
    val requestId: String
)

@Serializable
data class ApiErrorEnvelope(
    val error: ApiError,
    val requestId: String
)

@Serializable
data class ApiError(
    val code: String,
    val message: String
)
