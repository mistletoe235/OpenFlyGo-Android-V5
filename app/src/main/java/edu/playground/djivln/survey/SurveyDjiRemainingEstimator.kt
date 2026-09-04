package edu.playground.djivln.survey

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** Display-only estimation; never advances the flight controller or capture state. */
object SurveyDjiRemainingEstimator {
    private data class Projection(val fraction: Double, val error: Double, val point: GeoPoint)

    fun planned(mission: SurveyMission): SurveyRemainingEstimate = estimate(mission, 0, mission.waypoints.first().point)

    /** Resolve current-segment versus target-waypoint reports using adjacent geometry only. */
    fun segmentIndex(mission: SurveyMission, reportedIndex: Int, position: GeoPoint?, minimumIndex: Int = 0): Int {
        val last = mission.waypoints.lastIndex
        val minimum = minimumIndex.coerceIn(0, last)
        val anchor = reportedIndex.coerceIn(minimum, last)
        if (position == null || last == 0) return anchor
        val candidates = listOf(anchor - 1, anchor).filter { it >= minimum && it < last }
        var index = candidates.minWithOrNull(compareBy<Int> {
            project(mission.waypoints[it].point, mission.waypoints[it + 1].point, position).error
        }.thenByDescending { it }) ?: return last
        // Advance in route order at reached endpoints; never jump to a nearest future strip.
        while (index < last) {
            val start = mission.waypoints[index].point
            val end = mission.waypoints[index + 1].point
            val projection = project(start, end, position)
            // Coincident capture/rotation points need an SDK index change, not a GPS jump.
            val length = distance3d(start, end)
            if (length < 0.5) break
            if (distance3d(projection.point, end) > minOf(5.0, length * 0.1) || distance3d(position, end) > 5.0) break
            index++
        }
        return index
    }

    fun estimate(
        mission: SurveyMission,
        waypointIndex: Int,
        currentPosition: GeoPoint?,
        currentHeadingDegrees: Double = Double.NaN,
        currentHorizontalSpeedMetersPerSecond: Double = Double.NaN,
        currentVerticalSpeedMetersPerSecond: Double = Double.NaN,
        minimumSegmentIndex: Int = 0,
        isApproaching: Boolean = false,
        approachPoint: GeoPoint? = null,
    ): SurveyRemainingEstimate {
        val reported = waypointIndex.coerceIn(0, mission.waypoints.lastIndex)
        val position = if (isApproaching) approachPoint ?: mission.waypoints[reported].point else currentPosition
        val index = segmentIndex(mission, reported, position, minimumSegmentIndex)
        var section = 0.0
        var total = 0.0
        if (isApproaching && currentPosition != null && position != null) {
            val vertical = if (position.altitudeMeters < currentPosition.altitudeMeters) {
                mission.constraints.descentSpeedMetersPerSecond
            } else mission.constraints.takeoffSpeedMetersPerSecond
            // KMZ uses SAFELY: include vertical travel plus entry/recovery transit.
            total = horizontalDistance(currentPosition, position) / mission.constraints.takeoffSpeedMetersPerSecond.coerceAtLeast(0.2) +
                abs(position.altitudeMeters - currentPosition.altitudeMeters) / vertical.coerceAtLeast(0.2)
            section = total
        }
        if (index >= mission.waypoints.lastIndex) {
            val capture = SurveyEtaPolicy.captureDelaySeconds(mission.waypoints.last().captureAction)
            return SurveyRemainingEstimate(section + capture, total + capture)
        }
        val sectionPass = mission.waypoints[index + 1].passIndex
        for (startIndex in index until mission.waypoints.lastIndex) {
            val start = mission.waypoints[startIndex]
            val end = mission.waypoints[startIndex + 1]
            val projection = if (startIndex == index && position != null) project(start.point, end.point, position)
                else Projection(0.0, 0.0, start.point)
            val fullDistance = horizontalDistance(start.point, end.point)
            val remaining = fullDistance * (1.0 - projection.fraction)
            val configured = mission.constraints.speedForCaptureView(start.captureView).coerceAtLeast(0.2)
            val inCruise = !isApproaching && startIndex == index &&
                fullDistance * projection.fraction > maxOf(12.0, configured * 3) &&
                remaining > maxOf(12.0, configured * 3)
            val speed = if (inCruise) effectiveLiveSpeed(currentHorizontalSpeedMetersPerSecond, configured) else configured
            val verticalConfigured = (if (end.point.altitudeMeters < start.point.altitudeMeters) {
                mission.constraints.descentSpeedMetersPerSecond
            } else mission.constraints.takeoffSpeedMetersPerSecond).coerceAtLeast(0.2)
            val verticalSpeed = if (inCruise) effectiveLiveSpeed(abs(currentVerticalSpeedMetersPerSecond), verticalConfigured)
                else verticalConfigured
            var seconds = maxOf(remaining / speed,
                abs(end.point.altitudeMeters - projection.point.altitudeMeters) / verticalSpeed)
            if (startIndex == index && position != null) {
                seconds += maxOf(horizontalDistance(position, projection.point) / configured,
                    abs(position.altitudeMeters - projection.point.altitudeMeters) / verticalConfigured)
            }
            val next = mission.waypoints.getOrNull(startIndex + 2)
            val corner = next != null && fullDistance > 0.5 && horizontalDistance(end.point, next.point) > 0.5 &&
                shortestAngle(bearing(start.point, end.point), bearing(end.point, next.point)) > 6.0
            if (corner) seconds += 2.0
            val heading = if (startIndex == index && currentHeadingDegrees.isFinite()) currentHeadingDegrees else start.headingDegrees
            val yaw = shortestAngle(heading, end.headingDegrees)
            if (yaw > SurveyWaypointFollower.HEADING_TOLERANCE_DEGREES) {
                seconds += (if (corner) 0.0 else 2.0) + yaw / SurveyWaypointFollower.MAX_YAW_RATE_DEGREES_PER_SECOND
            }
            if (abs(start.gimbalPitchDegrees - end.gimbalPitchDegrees) > 5) seconds += 3.0
            seconds += SurveyEtaPolicy.captureDelaySeconds(end.captureAction)
            total += seconds
            if (end.passIndex == sectionPass) section += seconds
        }
        return SurveyRemainingEstimate(section, total)
    }

    private fun effectiveLiveSpeed(actual: Double, configured: Double): Double =
        if (!actual.isFinite() || actual < 0.1) configured else actual.coerceIn(configured * 0.35, configured * 1.25)

    private fun project(start: GeoPoint, end: GeoPoint, position: GeoPoint): Projection {
        val scale = 111_320.0 * cos(Math.toRadians((start.latitude + end.latitude) / 2))
        val x = (end.longitude - start.longitude) * scale
        val y = (end.latitude - start.latitude) * 111_132.0
        val z = end.altitudeMeters - start.altitudeMeters
        val px = (position.longitude - start.longitude) * scale
        val py = (position.latitude - start.latitude) * 111_132.0
        val pz = position.altitudeMeters - start.altitudeMeters
        val lengthSquared = x*x + y*y + z*z
        val fraction = if (lengthSquared < 0.000001) 1.0 else ((px*x + py*y + pz*z) / lengthSquared).coerceIn(0.0, 1.0)
        val point = GeoPoint(start.latitude + (end.latitude - start.latitude) * fraction,
            start.longitude + (end.longitude - start.longitude) * fraction, start.altitudeMeters + z * fraction)
        return Projection(fraction, distance3d(position, point), point)
    }

    private fun shortestAngle(left: Double, right: Double): Double = abs(((right - left) % 360 + 540) % 360 - 180)
    private fun bearing(a: GeoPoint, b: GeoPoint): Double = Math.toDegrees(atan2(
        (b.longitude-a.longitude) * cos(Math.toRadians((a.latitude+b.latitude)/2)), b.latitude-a.latitude))
    private fun horizontalDistance(a: GeoPoint, b: GeoPoint): Double = hypot(
        (b.latitude-a.latitude) * 111_132.0,
        (b.longitude-a.longitude) * 111_320.0 * cos(Math.toRadians((a.latitude+b.latitude)/2)))
    private fun distance3d(a: GeoPoint, b: GeoPoint) = hypot(horizontalDistance(a,b), a.altitudeMeters-b.altitudeMeters)
}
