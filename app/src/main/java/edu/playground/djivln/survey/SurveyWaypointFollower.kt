package edu.playground.djivln.survey

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class SurveyFollowerPose(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val headingDegrees: Double,
)

data class SurveyFollowerCommand(
    val reached: Boolean,
    val horizontalErrorMeters: Double,
    val verticalErrorMeters: Double,
    val forwardMetersPerSecond: Double,
    val rightMetersPerSecond: Double,
    val upMetersPerSecond: Double,
    val yawRateDegreesPerSecond: Double,
)

object SurveyWaypointFollower {
    const val HORIZONTAL_TOLERANCE_METERS = 1.2
    const val VERTICAL_TOLERANCE_METERS = 0.4
    const val HEADING_TOLERANCE_DEGREES = 6.0
    const val MAX_VERTICAL_SPEED_METERS_PER_SECOND = 0.5
    const val MAX_YAW_RATE_DEGREES_PER_SECOND = 30.0

    fun command(
        pose: SurveyFollowerPose,
        target: SurveyWaypoint,
        maximumHorizontalSpeedMetersPerSecond: Double,
        maximumVerticalSpeedMetersPerSecond: Double = MAX_VERTICAL_SPEED_METERS_PER_SECOND,
        alignHeadingBeforeHorizontalMotion: Boolean = false,
    ): SurveyFollowerCommand {
        require(maximumHorizontalSpeedMetersPerSecond in 0.1..10.0) {
            "survey speed must be in [0.1, 10.0] m/s"
        }
        require(maximumVerticalSpeedMetersPerSecond in 0.1..10.0) {
            "survey vertical speed must be in [0.1, 10.0] m/s"
        }
        val northError = (target.point.latitude - pose.latitude) * 111_132.0
        val eastError = (target.point.longitude - pose.longitude) * 111_320.0 *
            cos(Math.toRadians(pose.latitude))
        val horizontalError = hypot(northError, eastError)
        val verticalError = target.point.altitudeMeters - pose.altitudeMeters
        val headingError = wrapDegrees(target.headingDegrees - pose.headingDegrees)
        val reached = horizontalError <= HORIZONTAL_TOLERANCE_METERS &&
            abs(verticalError) <= VERTICAL_TOLERANCE_METERS &&
            abs(headingError) <= HEADING_TOLERANCE_DEGREES
        if (reached) {
            return SurveyFollowerCommand(true, horizontalError, verticalError, 0.0, 0.0, 0.0, 0.0)
        }

        val headingRadians = Math.toRadians(pose.headingDegrees)
        var forward = northError * cos(headingRadians) + eastError * sin(headingRadians)
        var right = -northError * sin(headingRadians) + eastError * cos(headingRadians)
        if (alignHeadingBeforeHorizontalMotion && abs(headingError) > HEADING_TOLERANCE_DEGREES) {
            forward = 0.0
            right = 0.0
        }
        forward *= 0.55
        right *= 0.55
        val horizontalSpeed = hypot(forward, right)
        if (horizontalSpeed > maximumHorizontalSpeedMetersPerSecond) {
            forward *= maximumHorizontalSpeedMetersPerSecond / horizontalSpeed
            right *= maximumHorizontalSpeedMetersPerSecond / horizontalSpeed
        }
        val up = (verticalError * 0.5).coerceIn(
            -maximumVerticalSpeedMetersPerSecond,
            maximumVerticalSpeedMetersPerSecond,
        )
        val yawRate = (headingError * 0.8).coerceIn(
            -MAX_YAW_RATE_DEGREES_PER_SECOND,
            MAX_YAW_RATE_DEGREES_PER_SECOND,
        )
        return SurveyFollowerCommand(
            reached = false,
            horizontalErrorMeters = horizontalError,
            verticalErrorMeters = verticalError,
            forwardMetersPerSecond = forward,
            rightMetersPerSecond = right,
            upMetersPerSecond = up,
            yawRateDegreesPerSecond = yawRate,
        )
    }

    private fun wrapDegrees(value: Double): Double {
        var wrapped = value % 360.0
        if (wrapped > 180.0) wrapped -= 360.0
        if (wrapped < -180.0) wrapped += 360.0
        return wrapped
    }
}
