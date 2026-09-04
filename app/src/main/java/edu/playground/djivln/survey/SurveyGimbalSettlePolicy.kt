package edu.playground.djivln.survey

import kotlin.math.abs

/** Bounded retry policy for camera-pitch transitions between survey view groups. */
object SurveyGimbalSettlePolicy {
    const val TOLERANCE_DEGREES = 3.0
    const val MIN_SETTLE_AFTER_ACCEPT_MS = 1_200L
    const val RETRY_INTERVAL_MS = 2_000L
    const val TIMEOUT_MS = 20_000L

    fun errorDegrees(targetPitchDegrees: Double, actualPitchDegrees: Double): Double =
        abs(targetPitchDegrees - actualPitchDegrees)

    fun isSettled(targetPitchDegrees: Double, actualPitchDegrees: Double): Boolean =
        errorDegrees(targetPitchDegrees, actualPitchDegrees) <= TOLERANCE_DEGREES

    fun isVerifiedForCapture(
        targetPitchDegrees: Double,
        actualPitchDegrees: Double,
        commandAcceptedElapsedMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean = commandAcceptedElapsedMillis > 0L &&
        nowElapsedMillis >= commandAcceptedElapsedMillis &&
        nowElapsedMillis - commandAcceptedElapsedMillis >= MIN_SETTLE_AFTER_ACCEPT_MS &&
        isSettled(targetPitchDegrees, actualPitchDegrees)

    /** Tracks only the current uninterrupted out-of-tolerance period. */
    fun updateUnsettledSince(
        nowElapsedMillis: Long,
        unsettledSinceElapsedMillis: Long,
        settled: Boolean,
    ): Long = when {
        settled -> 0L
        unsettledSinceElapsedMillis > 0L -> unsettledSinceElapsedMillis
        else -> nowElapsedMillis
    }

    fun shouldRetry(nowElapsedMillis: Long, lastCommandElapsedMillis: Long): Boolean =
        lastCommandElapsedMillis <= 0L ||
            nowElapsedMillis - lastCommandElapsedMillis >= RETRY_INTERVAL_MS

    fun hasTimedOut(nowElapsedMillis: Long, settlingStartedElapsedMillis: Long): Boolean =
        settlingStartedElapsedMillis > 0L &&
            nowElapsedMillis - settlingStartedElapsedMillis >= TIMEOUT_MS
}
