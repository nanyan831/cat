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
        throw MemoryHttpException(
            response.code,
            envelope?.error?.message ?: "服务器拒绝了请求。"
        )
    }

    private fun <T> parse(response: Response, type: Class<T>): T =
        response.body?.charStream()?.use { gson.fromJson(it, type) }
            ?: throw MemoryHttpException(response.code, "服务器没有返回内容。")

    private fun url(path: String) = baseUrl.newBuilder().addPathSegments(path).build()

    private data class CreateMemoryDto(val kind: String, val content: String)
    private data class MemoryListDto(val memories: List<CompanionMemory>)
    private data class ErrorEnvelopeDto(val error: ErrorDto?)
    private data class ErrorDto(val message: String?)
    private class MemoryHttpException(val status: Int, val userMessage: String) : IOException(userMessage)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
