package edu.playground.djivln.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogSanitizerTest {
    @Test fun redactsSensitiveFieldsAndInlineCredentials() {
        val sanitized = DiagnosticLogSanitizer.sanitizeFields(
            mapOf(
                "access_token" to "secret-token",
                "message" to "authorization=abc password:xyz Bearer raw.jwt.value",
                "safe" to "visible",
            ),
        )

        assertEquals("<redacted>", sanitized["access_token"])
        assertEquals("visible", sanitized["safe"])
        val message = sanitized["message"].toString()
        assertFalse(message.contains("abc"))
        assertFalse(message.contains("xyz"))
        assertFalse(message.contains("raw.jwt.value"))
        assertTrue(message.contains("<redacted>"))
    }

    @Test fun redactsSensitiveNestedMapValues() {
        val sanitized = DiagnosticLogSanitizer.sanitizeFields(
            mapOf("request" to mapOf("cookie" to "session", "status" to 500)),
        )

        val request = sanitized["request"] as Map<*, *>
        assertEquals("<redacted>", request["cookie"])
        assertEquals(500, request["status"])
    }
}
