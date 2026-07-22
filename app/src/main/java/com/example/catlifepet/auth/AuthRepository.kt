package com.example.catlifepet.auth

import com.google.gson.Gson
import retrofit2.Response
import java.io.IOException

sealed interface AuthOutcome<out T> {
    data class Success<T>(val value: T) : AuthOutcome<T>
    data class Failure(
        val code: String,
        val message: String,
        val httpStatus: Int? = null,
        val retryable: Boolean = false
    ) : AuthOutcome<Nothing>
}

class AuthRepository(
    private val api: AuthApi,
    private val sessionStore: SessionStore,
    private val tokenProvider: AccessTokenProvider,
    private val deviceLabel: String,
    private val gson: Gson = Gson()
) {
    @Volatile
    var currentUser: UserProfile? = null
        private set

    fun hasStoredSession(): Boolean = sessionStore.readRefreshToken() != null

    suspend fun requestCode(email: String): AuthOutcome<RequestLoginCodeResponse> {
        return executePublic { api.requestCode(RequestLoginCodeRequest(email.trim())) }
    }

    suspend fun verifyCode(email: String, code: String): AuthOutcome<UserProfile> {
        return when (
            val outcome = executePublic {
                api.verifyCode(VerifyLoginCodeRequest(email.trim(), code.trim(), deviceLabel))
            }
        ) {
            is AuthOutcome.Failure -> outcome
            is AuthOutcome.Success -> acceptSession(outcome.value)
        }
    }

    suspend fun restoreSession(): AuthOutcome<UserProfile> {
        val refreshToken = sessionStore.readRefreshToken()
            ?: return AuthOutcome.Failure("logged_out", "No saved session.")
        return refresh(refreshToken)
    }

    suspend fun getMe(): AuthOutcome<UserProfile> {
        return when (val outcome = executeAuthenticated { api.getMe() }) {
            is AuthOutcome.Failure -> outcome
            is AuthOutcome.Success -> outcome.also { currentUser = it.value }
        }
    }

    suspend fun updateProfile(displayName: String?, timeZone: String?): AuthOutcome<UserProfile> {
        val request = UpdateProfileRequest(displayName?.trim(), timeZone?.trim())
        return when (val outcome = executeAuthenticated { api.updateProfile(request) }) {
            is AuthOutcome.Failure -> outcome
            is AuthOutcome.Success -> outcome.also { currentUser = it.value }
        }
    }

    suspend fun logout(): AuthOutcome<Unit> {
        val hadCredential = hasStoredSession()
        val remoteOutcome = if (hadCredential) {
            when (val ready = ensureAccessToken()) {
                is AuthOutcome.Failure -> ready
                is AuthOutcome.Success -> executeAuthenticated { api.logout() }
            }
        } else {
            AuthOutcome.Success(ActionResponse(true))
        }
        clearLocalSession()
        return when (remoteOutcome) {
            is AuthOutcome.Failure -> AuthOutcome.Success(Unit)
            is AuthOutcome.Success -> AuthOutcome.Success(Unit)
        }
    }

    suspend fun deleteAccount(): AuthOutcome<Unit> {
        return when (val outcome = executeAuthenticated { api.deleteAccount() }) {
            is AuthOutcome.Failure -> outcome
            is AuthOutcome.Success -> {
                clearLocalSession()
                AuthOutcome.Success(Unit)
            }
        }
    }

    fun clearLocalSession() {
        tokenProvider.accessToken = null
        currentUser = null
        sessionStore.clear()
    }

    private suspend fun ensureAccessToken(): AuthOutcome<Unit> {
        if (!tokenProvider.accessToken.isNullOrBlank()) return AuthOutcome.Success(Unit)
        return when (val restored = restoreSession()) {
            is AuthOutcome.Failure -> restored
            is AuthOutcome.Success -> AuthOutcome.Success(Unit)
        }
    }

    private suspend fun refresh(refreshToken: String): AuthOutcome<UserProfile> {
        val outcome = executePublic {
            api.refresh(RefreshSessionRequest(refreshToken, deviceLabel))
        }
        return when (outcome) {
            is AuthOutcome.Success -> acceptSession(outcome.value)
            is AuthOutcome.Failure -> {
                if (outcome.httpStatus == 401) clearLocalSession()
                outcome
            }
        }
    }

    private fun acceptSession(session: AuthSessionResponse): AuthOutcome<UserProfile> {
        return runCatching {
            sessionStore.writeRefreshToken(session.refreshToken)
            tokenProvider.accessToken = session.accessToken
            currentUser = session.user
            session.user
        }.fold(
            onSuccess = AuthOutcome<UserProfile>::Success,
            onFailure = {
                tokenProvider.accessToken = null
                currentUser = null
                runCatching { sessionStore.clear() }
                AuthOutcome.Failure(
                    code = "secure_storage_error",
                    message = "The secure session could not be saved."
                )
            }
        )
    }

    private suspend fun <T> executeAuthenticated(call: suspend () -> Response<T>): AuthOutcome<T> {
        when (val ready = ensureAccessToken()) {
            is AuthOutcome.Failure -> return ready
            is AuthOutcome.Success -> Unit
        }
        return try {
            var response = call()
            if (response.code() == 401) {
                val refreshToken = sessionStore.readRefreshToken()
                    ?: return AuthOutcome.Failure("logged_out", "No saved session.")
                when (val refreshed = refresh(refreshToken)) {
                    is AuthOutcome.Failure -> return refreshed
                    is AuthOutcome.Success -> Unit
                }
                response = call()
            }
            response.toOutcome()
        } catch (error: IOException) {
            networkFailure(error)
        } catch (error: RuntimeException) {
            AuthOutcome.Failure("client_error", "The response could not be processed.")
        }
    }

    private suspend fun <T> executePublic(call: suspend () -> Response<T>): AuthOutcome<T> {
        return try {
            call().toOutcome()
        } catch (error: IOException) {
            networkFailure(error)
        } catch (error: RuntimeException) {
            AuthOutcome.Failure("client_error", "The response could not be processed.")
        }
    }

    private fun <T> Response<T>.toOutcome(): AuthOutcome<T> {
        if (isSuccessful) {
            val value = body()
                ?: return AuthOutcome.Failure("empty_response", "The server returned an empty response.", code())
            return AuthOutcome.Success(value)
        }
        val envelope = runCatching {
            errorBody()?.charStream()?.use { gson.fromJson(it, ApiErrorEnvelope::class.java) }
        }.getOrNull()
        return AuthOutcome.Failure(
            code = envelope?.error?.code ?: "http_error",
            message = envelope?.error?.message ?: "The server rejected the request.",
            httpStatus = code(),
            retryable = code() >= 500
        )
    }

    private fun networkFailure(error: IOException): AuthOutcome.Failure {
        return AuthOutcome.Failure(
            code = "network_error",
            message = error.message ?: "The server could not be reached.",
            retryable = true
        )
    }
}
