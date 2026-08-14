package com.example.catlifepet.memory

import com.example.catlifepet.auth.AuthOutcome
import com.example.catlifepet.auth.AuthRepository
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MemoryRepository(
    baseUrl: String,
    private val client: OkHttpClient,
    private val gson: Gson,
    private val authRepository: AuthRepository
) : MemoryDataSource {
    private val baseUrl = baseUrl.toHttpUrl()

    override suspend fun list(): MemoryOutcome<List<CompanionMemory>> = request {
        val response = execute(Request.Builder().url(url("v1/memories")).get().build())
        response.use { requireSuccess(it); parse(it, MemoryListDto::class.java).memories }
    }

    override suspend fun create(kind: String, content: String): MemoryOutcome<CompanionMemory> = request {
        val body = gson.toJson(CreateMemoryDto(kind, content)).toRequestBody(JSON)
        val response = execute(Request.Builder().url(url("v1/memories")).post(body).build())
        response.use { requireSuccess(it); parse(it, CompanionMemory::class.java) }
    }

    override suspend fun delete(memoryId: String): MemoryOutcome<Unit> = deletePath("v1/memories/$memoryId")
    override suspend fun deleteAll(): MemoryOutcome<Unit> = deletePath("v1/memories")
    override suspend fun clearConversations(): MemoryOutcome<Unit> = deletePath("v1/conversations")

    private suspend fun deletePath(path: String): MemoryOutcome<Unit> = request {
        execute(Request.Builder().url(url(path)).delete().build()).use(::requireSuccess)
    }

    private suspend fun <T> request(block: suspend () -> T): MemoryOutcome<T> = withContext(Dispatchers.IO) {
        try {
            when (authRepository.ensureAuthenticated()) {
                is AuthOutcome.Failure -> return@withContext MemoryOutcome.SessionExpired
                is AuthOutcome.Success -> Unit
            }
            try {
                MemoryOutcome.Success(block())
            } catch (first: MemoryHttpException) {
                if (first.status != 401) throw first
                when (authRepository.ensureAuthenticated(forceRefresh = true)) {
                    is AuthOutcome.Failure -> MemoryOutcome.SessionExpired
                    is AuthOutcome.Success -> MemoryOutcome.Success(block())
                }
            }
        } catch (error: MemoryHttpException) {
            if (error.status == 401) MemoryOutcome.SessionExpired
            else MemoryOutcome.Failure(error.userMessage)
        } catch (_: IOException) {
            MemoryOutcome.Failure("网络连接中断，请稍后重试。")
        } catch (_: RuntimeException) {
            MemoryOutcome.Failure("操作没有完成，请稍后重试。")
        }
    }

    private fun execute(request: Request) = client.newCall(request).execute()

    private fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        val envelope = runCatching {
            response.body?.charStream()?.use { gson.fromJson(it, ErrorEnvelopeDto::class.java) }
        }.getOrNull()
        val errorCode = envelope?.error?.code ?: "http_error"
        throw MemoryHttpException(
            response.code,
            friendlyMemoryMessage(response.code, errorCode)
        )
    }

    private fun <T> parse(response: Response, type: Class<T>): T =
        response.body?.charStream()?.use { gson.fromJson(it, type) }
            ?: throw MemoryHttpException(response.code, "服务器没有返回内容。")

    private fun url(path: String) = baseUrl.newBuilder().addPathSegments(path).build()

    private fun friendlyMemoryMessage(status: Int, code: String): String = when (code) {
        "invalid_access_token", "missing_access_token", "token_expired" -> "登录已失效，请重新登录。"
        "invalid_request" -> "记忆内容格式不正确，请检查后再试。"
        "not_found" -> "这条记忆已经不存在了，请刷新后再试。"
        "rate_limited" -> "操作有些频繁，请稍后再试。"
        "internal_error" -> "服务器暂时不可用，请稍后重试。"
        "http_error" -> friendlyMemoryHttpMessage(status)
        else -> friendlyMemoryHttpMessage(status)
    }

    private fun friendlyMemoryHttpMessage(status: Int): String = when (status) {
        400, 422 -> "记忆内容格式不正确，请检查后再试。"
        401 -> "登录已失效，请重新登录。"
        403 -> "云端服务暂时不可用，请稍后重试。"
        404 -> "这条记忆已经不存在了，请刷新后再试。"
        408 -> "服务器响应超时，请稍后重试。"
        409 -> "当前记忆状态已变化，请刷新后再试。"
        429 -> "操作有些频繁，请稍后再试。"
        in 500..599 -> "服务器暂时不可用，请稍后重试。"
        else -> "操作没有完成，请稍后重试。"
    }

    private data class CreateMemoryDto(val kind: String, val content: String)
    private data class MemoryListDto(val memories: List<CompanionMemory>)
    private data class ErrorEnvelopeDto(val error: ErrorDto?)
    private data class ErrorDto(val code: String?, val message: String?)
    private class MemoryHttpException(val status: Int, val userMessage: String) : IOException(userMessage)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
