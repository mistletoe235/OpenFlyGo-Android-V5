package edu.playground.djivln.control

import kotlin.math.round

object HorizontalSpeedSetting {
    const val MIN_METERS_PER_SECOND = 0.2
    const val MAX_METERS_PER_SECOND = 4.0
    const val DEFAULT_METERS_PER_SECOND = 1.0
    const val STEP_METERS_PER_SECOND = 0.1

    fun normalize(value: Double?): Double {
        if (value == null || !value.isFinite()) return DEFAULT_METERS_PER_SECOND
        val stepped = round(value / STEP_METERS_PER_SECOND) * STEP_METERS_PER_SECOND
        return stepped.coerceIn(MIN_METERS_PER_SECOND, MAX_METERS_PER_SECOND)
    }

    fun adjusted(current: Double, deltaSteps: Int): Double =
        normalize(current + deltaSteps * STEP_METERS_PER_SECOND)
}
