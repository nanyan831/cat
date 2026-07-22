package com.example.catlifepet.server.http

internal object SanitizedRequestLog {
    private const val MAX_PATH_LENGTH = 256

    fun format(method: String, rawPath: String, statusCode: Int?): String {
        val safeMethod = method.filter { it.isLetter() }.take(12).ifBlank { "UNKNOWN" }
        val safePath = rawPath
            .substringBefore('?')
            .filterNot(Char::isISOControl)
            .take(MAX_PATH_LENGTH)
            .ifBlank { "/" }
        return "$safeMethod $safePath status=${statusCode ?: 0}"
    }
}
