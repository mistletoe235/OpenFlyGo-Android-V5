package edu.playground.djivln.logging

object DiagnosticLogSanitizer {
    private val sensitiveKey = Regex(
        pattern = "(?i)(password|passwd|token|authorization|cookie|secret|api[_-]?key)",
    )
    private val assignment = Regex(
        pattern = "(?i)\\b(password|passwd|token|authorization|cookie|secret|api[_-]?key)\\b\\s*[:=]\\s*([^\\s,;]+)",
    )
    private val bearer = Regex(pattern = "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")

    fun sanitizeFields(fields: Map<String, Any?>): Map<String, Any?> = fields.mapValues { (key, value) ->
        if (sensitiveKey.containsMatchIn(key)) REDACTED else sanitizeValue(value)
    }

    fun sanitizeText(value: String): String = value
        .replace(assignment) { match -> "${match.groupValues[1]}=$REDACTED" }
        .replace(bearer, "Bearer $REDACTED")

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is String -> sanitizeText(value)
        is Map<*, *> -> value.entries.associate { (key, nestedValue) ->
            val nestedKey = key?.toString().orEmpty()
            nestedKey to if (sensitiveKey.containsMatchIn(nestedKey)) REDACTED else sanitizeValue(nestedValue)
        }
        is Iterable<*> -> value.map(::sanitizeValue)
        is Array<*> -> value.map(::sanitizeValue)
        else -> value
    }

    private const val REDACTED = "<redacted>"
}
