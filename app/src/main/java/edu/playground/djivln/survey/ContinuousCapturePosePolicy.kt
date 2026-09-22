package edu.playground.djivln.survey

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

object ContinuousCapturePosePolicy {
    fun ready(aircraft: AircraftSnapshot, target: SurveyWaypoint, nowNanos: Long): Boolean {
        fun fresh(updatedAt: Long): Boolean = updatedAt > 0L && nowNanos >= updatedAt &&
            nowNanos - updatedAt <= 1_000_000_000L
        val heading = aircraft.headingDegrees ?: return false
        val pitch = aircraft.gimbalPitchDegrees ?: return false
        val altitude = aircraft.relativeAltitudeMeters ?: return false
        return aircraft.connected && fresh(aircraft.aircraftLocationUpdatedAtNanos) &&
            fresh(aircraft.relativeAltitudeUpdatedAtNanos) && fresh(aircraft.headingUpdatedAtNanos) &&
            fresh(aircraft.gimbalAttitudeUpdatedAtNanos) &&
            abs(((heading - target.headingDegrees) % 360.0 + 540.0) % 360.0 - 180.0) <= 3.0 &&
            SurveyGimbalSettlePolicy.isSettled(target.gimbalPitchDegrees, pitch) &&
            abs(altitude - target.point.altitudeMeters) <= 2.0
    }
}

/**
 * A stopped capture must not be released merely because DJI reports that the
 * waypoint index has been reached. Aircraft yaw and gimbal pitch can continue
 * moving after the position callback, especially at the first point of a new
 * pass. Require fresh pose, low speed, tight position/altitude error and a
 * continuous stable dwell before the Android camera trigger is allowed.
 */
object StoppedCapturePosePolicy {
    const val REQUIRED_STABLE_MILLIS = 800L
    const val MAX_HORIZONTAL_SPEED_METERS_PER_SECOND = 0.35
    const val MAX_HORIZONTAL_ERROR_METERS = 1.5
    const val MAX_ALTITUDE_ERROR_METERS = 1.0
    const val MAX_HEADING_ERROR_DEGREES = 3.0
    private const val MAX_SAMPLE_AGE_NANOS = 1_000_000_000L

    fun aligned(
        aircraft: AircraftSnapshot,
        target: SurveyWaypoint,
        position: GeoPoint,
        horizontalSpeedMetersPerSecond: Double,
        nowNanos: Long,
    ): Boolean {
        fun fresh(updatedAt: Long): Boolean = updatedAt > 0L && nowNanos >= updatedAt &&
            nowNanos - updatedAt <= MAX_SAMPLE_AGE_NANOS
        val heading = aircraft.headingDegrees ?: return false
        val pitch = aircraft.gimbalPitchDegrees ?: return false
        val altitude = aircraft.relativeAltitudeMeters ?: return false
        return aircraft.connected &&
            fresh(aircraft.aircraftLocationUpdatedAtNanos) &&
            fresh(aircraft.relativeAltitudeUpdatedAtNanos) &&
            fresh(aircraft.velocityUpdatedAtNanos) &&
            fresh(aircraft.headingUpdatedAtNanos) &&
            fresh(aircraft.gimbalAttitudeUpdatedAtNanos) &&
            horizontalSpeedMetersPerSecond <= MAX_HORIZONTAL_SPEED_METERS_PER_SECOND &&
            distanceMeters(position, target.point) <= MAX_HORIZONTAL_ERROR_METERS &&
            abs(altitude - target.point.altitudeMeters) <= MAX_ALTITUDE_ERROR_METERS &&
            angleDifference(heading, target.headingDegrees) <= MAX_HEADING_ERROR_DEGREES &&
            SurveyGimbalSettlePolicy.isSettled(target.gimbalPitchDegrees, pitch)
    }

    fun updatedStableSince(
        aligned: Boolean,
        previousStableSinceMillis: Long,
        nowElapsedMillis: Long,
    ): Long = when {
        !aligned -> 0L
        previousStableSinceMillis > 0L -> previousStableSinceMillis
        else -> nowElapsedMillis
    }

    fun stable(stableSinceMillis: Long, nowElapsedMillis: Long): Boolean =
        stableSinceMillis > 0L && nowElapsedMillis >= stableSinceMillis &&
            nowElapsedMillis - stableSinceMillis >= REQUIRED_STABLE_MILLIS

    private fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
        val north = (second.latitude - first.latitude) * 111_132.0
        val east = (second.longitude - first.longitude) * 111_320.0 *
            cos(Math.toRadians((first.latitude + second.latitude) / 2.0))
        return hypot(north, east)
    }

    private fun angleDifference(first: Double, second: Double): Double =
        abs(((second - first) % 360.0 + 540.0) % 360.0 - 180.0)
}
