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

fun AuthOutcome.Failure.toUserMessage(): String = when (code) {
    "invalid_code" -> "验证码错误或已过期，请重新输入。"
    "code_attempts_exceeded" -> "尝试次数过多，请重新发送验证码。"
    "code_send_failed" -> "验证码暂时发送失败，请稍后再试。"
    "rate_limited" -> "操作有些频繁，请稍后再试。"
    "invalid_session",
    "session_replay_detected",
    "invalid_access_token",
    "missing_access_token",
    "token_expired",
    "logged_out" -> "登录已失效，请重新登录。"
    "secure_storage_error" -> "无法安全保存登录状态，请检查设备安全设置。"
    "invalid_request" -> "输入内容不正确，请检查后重试。"
    "empty_response" -> "服务器没有返回内容，请稍后重试。"
    "network_error" -> "无法连接服务器，请检查网络后重试。"
    "client_error" -> "数据解析失败，请稍后重试。"
    "http_error" -> friendlyAuthHttpMessage(httpStatus)
    else -> if (retryable) "服务器暂时不可用，请稍后重试。" else friendlyAuthHttpMessage(httpStatus)
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
            ?: return AuthOutcome.Failure("logged_out", "登录已失效，请重新登录。")
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

    suspend fun ensureAuthenticated(forceRefresh: Boolean = false): AuthOutcome<Unit> {
        if (!forceRefresh && !tokenProvider.accessToken.isNullOrBlank()) return AuthOutcome.Success(Unit)
        val refreshToken = sessionStore.readRefreshToken()
            ?: return AuthOutcome.Failure("logged_out", "登录已失效，请重新登录。", httpStatus = 401)
        return when (val restored = refresh(refreshToken)) {
            is AuthOutcome.Failure -> restored
            is AuthOutcome.Success -> AuthOutcome.Success(Unit)
        }
    }

    private suspend fun ensureAccessToken(): AuthOutcome<Unit> {
        return ensureAuthenticated()
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
                    message = "无法安全保存登录状态，请检查设备安全设置。"
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
                    ?: return AuthOutcome.Failure("logged_out", "登录已失效，请重新登录。")
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
            AuthOutcome.Failure("client_error", "数据解析失败，请稍后重试。")
        }
    }

    private suspend fun <T> executePublic(call: suspend () -> Response<T>): AuthOutcome<T> {
        return try {
            call().toOutcome()
        } catch (error: IOException) {
            networkFailure(error)
        } catch (error: RuntimeException) {
            AuthOutcome.Failure("client_error", "数据解析失败，请稍后重试。")
        }
    }

    private fun <T> Response<T>.toOutcome(): AuthOutcome<T> {
        if (isSuccessful) {
            val value = body()
                ?: return AuthOutcome.Failure("empty_response", "服务器没有返回内容，请稍后重试。", code())
            return AuthOutcome.Success(value)
        }
        val envelope = runCatching {
            errorBody()?.charStream()?.use { gson.fromJson(it, ApiErrorEnvelope::class.java) }
        }.getOrNull()
        val failure = AuthOutcome.Failure(
            code = envelope?.error?.code ?: "http_error",
            message = envelope?.error?.message.orEmpty(),
            httpStatus = code(),
            retryable = code() >= 500
        )
        return failure.copy(message = failure.toUserMessage())
    }

    private fun networkFailure(error: IOException): AuthOutcome.Failure {
        return AuthOutcome.Failure(
            code = "network_error",
            message = "无法连接服务器，请检查网络后重试。",
            retryable = true
        )
    }
}

private fun friendlyAuthHttpMessage(status: Int?): String = when (status) {
    400, 422 -> "输入内容不正确，请检查后重试。"
    401 -> "登录已失效，请重新登录。"
    403 -> "云端服务暂时不可用，请稍后重试。"
    404 -> "请求的内容暂时找不到了，请稍后重试。"
    408 -> "服务器响应超时，请稍后重试。"
    409 -> "当前状态已变化，请刷新后重试。"
    429 -> "操作有些频繁，请稍后再试。"
    in 500..599 -> "服务器暂时不可用，请稍后重试。"
    else -> "操作没有完成，请稍后重试。"
}
