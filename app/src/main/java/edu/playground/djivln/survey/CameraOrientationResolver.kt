package edu.playground.djivln.survey

import kotlin.math.abs

data class ResolvedCameraOrientation(
    val rollDegrees: Double?,
    val pitchDegrees: Double?,
    val yawDegrees: Double?,
    val yawSource: String?,
    val yawConsistencyErrorDegrees: Double?,
)

/** Resolves camera attitude without assuming every DJI product exposes identical yaw semantics. */
object CameraOrientationResolver {
    const val SOURCE_HEADING_PLUS_RELATIVE_GIMBAL = "aircraft_heading_plus_gimbal_relative"
    const val SOURCE_ABSOLUTE_GIMBAL_CROSS_CHECKED = "absolute_gimbal_attitude_cross_checked"
    const val SOURCE_HEADING_PLUS_RELATIVE_GIMBAL_CONFLICT =
        "aircraft_heading_plus_gimbal_relative_absolute_conflict"
    const val SOURCE_ABSOLUTE_GIMBAL = "absolute_gimbal_attitude"
    const val SOURCE_AIRCRAFT_HEADING_FALLBACK = "aircraft_heading_fallback"

    fun resolve(
        aircraftHeadingDegrees: Double?,
        gimbalRollDegrees: Double?,
        gimbalPitchDegrees: Double?,
        absoluteGimbalYawDegrees: Double?,
        relativeGimbalYawDegrees: Double?,
    ): ResolvedCameraOrientation {
        val heading = aircraftHeadingDegrees.finiteOrNull()?.let(::normalizeHeading)
        val absoluteYaw = absoluteGimbalYawDegrees.finiteOrNull()?.let(::normalizeHeading)
        val relativeYaw = relativeGimbalYawDegrees.finiteOrNull()
        val composedYaw = if (heading != null && relativeYaw != null) {
            normalizeHeading(heading + relativeYaw)
        } else {
            null
        }
        val consistencyError = if (composedYaw != null && absoluteYaw != null) {
            abs(shortestAngleDegrees(composedYaw - absoluteYaw))
        } else {
            null
        }
        val (yaw, source) = when {
            composedYaw != null && absoluteYaw != null &&
                requireNotNull(consistencyError) <= MAX_CROSS_CHECK_ERROR_DEGREES ->
                absoluteYaw to SOURCE_ABSOLUTE_GIMBAL_CROSS_CHECKED
            composedYaw != null && absoluteYaw != null ->
                composedYaw to SOURCE_HEADING_PLUS_RELATIVE_GIMBAL_CONFLICT
            composedYaw != null -> composedYaw to SOURCE_HEADING_PLUS_RELATIVE_GIMBAL
            absoluteYaw != null -> absoluteYaw to SOURCE_ABSOLUTE_GIMBAL
            heading != null -> heading to SOURCE_AIRCRAFT_HEADING_FALLBACK
            else -> null to null
        }
        return ResolvedCameraOrientation(
            rollDegrees = gimbalRollDegrees.finiteOrNull(),
            pitchDegrees = gimbalPitchDegrees.finiteOrNull(),
            yawDegrees = yaw,
            yawSource = source,
            yawConsistencyErrorDegrees = consistencyError,
        )
    }

    private fun Double?.finiteOrNull(): Double? = this?.takeIf(Double::isFinite)

    private fun normalizeHeading(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    private fun shortestAngleDegrees(value: Double): Double =
        ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    private const val MAX_CROSS_CHECK_ERROR_DEGREES = 5.0
}
