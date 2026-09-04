package edu.playground.djivln.survey

import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import kotlin.math.cos
import kotlin.math.max

object SurveyDjiBreakpointEstimator {
    fun refine(
        mission: SurveyMission,
        breakpoint: WaylineBreakpoint,
        aircraftLatitude: Double?,
        aircraftLongitude: Double?,
    ): WaylineBreakpoint {
        if (aircraftLatitude == null || aircraftLongitude == null) return breakpoint
        val start = mission.waypoints.getOrNull(breakpoint.waypointId)?.point ?: return breakpoint
        val end = mission.waypoints.getOrNull(breakpoint.waypointId + 1)?.point ?: return breakpoint
        val referenceLatitude = Math.toRadians((start.latitude + end.latitude) / 2.0)
        val longitudeScale = METERS_PER_DEGREE * cos(referenceLatitude)
        val segmentEast = (end.longitude - start.longitude) * longitudeScale
        val segmentNorth = (end.latitude - start.latitude) * METERS_PER_DEGREE
        val segmentLengthSquared = segmentEast * segmentEast + segmentNorth * segmentNorth
        if (segmentLengthSquared < MINIMUM_SEGMENT_LENGTH_METERS * MINIMUM_SEGMENT_LENGTH_METERS) return breakpoint
        val aircraftEast = (aircraftLongitude - start.longitude) * longitudeScale
        val aircraftNorth = (aircraftLatitude - start.latitude) * METERS_PER_DEGREE
        val projectedProgress = ((aircraftEast * segmentEast + aircraftNorth * segmentNorth) / segmentLengthSquared)
            .coerceIn(MINIMUM_RESUMABLE_PROGRESS, MAXIMUM_RESUMABLE_PROGRESS)
        return breakpoint.copy(segmentProgress = max(breakpoint.segmentProgress, projectedProgress))
    }

    private const val METERS_PER_DEGREE = 111_320.0
    private const val MINIMUM_SEGMENT_LENGTH_METERS = 0.5
    private const val MINIMUM_RESUMABLE_PROGRESS = 0.001
    private const val MAXIMUM_RESUMABLE_PROGRESS = 0.999
}
