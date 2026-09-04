package edu.playground.djivln.reconstruction

/** Upload retry decisions are explicit so permanent API errors do not create a retry storm. */
object V86UploadRetryPolicy {
    fun isAutomaticallyRetryable(error: Throwable): Boolean =
        error !is V86HttpException || error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500

    fun delayMillis(attempt: Int): Long {
        val exponent = attempt.coerceIn(1, MAX_EXPONENT) - 1
        return (INITIAL_DELAY_MILLIS shl exponent).coerceAtMost(MAX_DELAY_MILLIS)
    }

    const val INITIAL_DELAY_MILLIS = 2_000L
    const val MAX_DELAY_MILLIS = 60_000L
    private const val MAX_EXPONENT = 6
}
