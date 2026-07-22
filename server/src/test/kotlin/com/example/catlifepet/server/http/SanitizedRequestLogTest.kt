package com.example.catlifepet.server.http

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SanitizedRequestLogTest {
    @Test
    fun `query values and control characters are not logged`() {
        val output = SanitizedRequestLog.format(
            method = "GET\nAuthorization",
            rawPath = "/health?token=secret-value\nBearer hidden",
            statusCode = 200
        )

        assertTrue(output.startsWith("GETAuthoriza /health status=200"))
        assertFalse(output.contains("secret-value"))
        assertFalse(output.contains("Bearer"))
        assertFalse(output.contains('\n'))
    }
}
