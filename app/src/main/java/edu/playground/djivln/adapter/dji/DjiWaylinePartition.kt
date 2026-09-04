package edu.playground.djivln.adapter.dji

import android.content.Context
import edu.playground.djivln.R
import edu.playground.djivln.survey.SurveyMission
import edu.playground.djivln.survey.surveyPasses
import kotlin.math.cos
import kotlin.math.hypot

data class DjiWaylineSegment(
    val waylineId: Int,
    val firstGlobalWaypointIndex: Int,
    val lastGlobalWaypointIndex: Int,
    val mission: SurveyMission,
) {
    fun globalWaypointIndex(localWaypointIndex: Int): Int =
        (firstGlobalWaypointIndex + localWaypointIndex).coerceIn(firstGlobalWaypointIndex, lastGlobalWaypointIndex)
}

object DjiWaylinePartition {
    const val MAX_WAYPOINTS_PER_WAYLINE = 190

    fun segments(mission: SurveyMission, context: Context? = null): List<DjiWaylineSegment> {
        if (mission.waypoints.size <= MAX_WAYPOINTS_PER_WAYLINE) {
            return listOf(segment(mission, 0, 0, mission.waypoints.lastIndex, context))
        }
        val ranges = mutableListOf<IntRange>()
        var currentStart = -1
        var currentEnd = -1
        mission.surveyPasses().forEach { pass ->
            require(pass.waypoints.size <= MAX_WAYPOINTS_PER_WAYLINE) {
                "survey unit ${pass.start.passIndex} exceeds DJI wayline capacity"
            }
            val proposedSize = if (currentStart < 0) {
                pass.waypoints.size
            } else {
                pass.lastWaypointIndex - currentStart + 1
            }
            if (currentStart >= 0 && proposedSize > MAX_WAYPOINTS_PER_WAYLINE) {
                ranges += currentStart..currentEnd
                currentStart = pass.firstWaypointIndex
            } else if (currentStart < 0) {
                currentStart = pass.firstWaypointIndex
            }
            currentEnd = pass.lastWaypointIndex
        }
        if (currentStart >= 0) ranges += currentStart..currentEnd
        return ranges.mapIndexed { index, range -> segment(mission, index, range.first, range.last, context) }
    }

    fun globalWaypointIndex(
        mission: SurveyMission,
        waylineId: Int?,
        localWaypointIndex: Int?,
        context: Context? = null,
    ): Int? {
        if (localWaypointIndex == null) return null
        val segment = segments(mission, context).firstOrNull { it.waylineId == (waylineId ?: 0) } ?: return null
        return segment.globalWaypointIndex(localWaypointIndex)
    }

    fun remainingWaylineIds(
        mission: SurveyMission,
        completedWaylineId: Int,
        context: Context? = null,
    ): List<Int> = segments(mission, context).map { it.waylineId }.filter { it > completedWaylineId }

    fun reachedSegmentEnd(
        mission: SurveyMission,
        waylineId: Int?,
        localWaypointIndex: Int?,
        context: Context? = null,
    ): Boolean {
        if (waylineId == null || localWaypointIndex == null) return false
        val segment = segments(mission, context).firstOrNull { it.waylineId == waylineId } ?: return false
        return localWaypointIndex >= segment.mission.waypoints.lastIndex
    }

    private fun segment(
        mission: SurveyMission,
        waylineId: Int,
        first: Int,
        last: Int,
        context: Context?,
    ): DjiWaylineSegment {
        val waypoints = mission.waypoints.subList(first, last + 1)
        val pathMeters = waypoints.zipWithNext().sumOf { (start, end) ->
            distanceMeters(start.point.latitude, start.point.longitude, end.point.latitude, end.point.longitude)
        }
        val ratio = if (mission.estimatedPathMeters > 0.0) pathMeters / mission.estimatedPathMeters else 0.0
        val segmentMission = mission.copy(
            id = "${mission.id}-wayline-$waylineId",
            name = context?.getString(R.string.internal_wayline_segment_name, mission.name, waylineId + 1)
                ?: "${mission.name} · Internal segment ${waylineId + 1}",
            waypoints = waypoints,
            estimatedPathMeters = pathMeters,
            estimatedPhotoCount = (mission.estimatedPhotoCount * ratio).toInt().coerceAtLeast(0),
            estimatedFlightSeconds = if (mission.constraints.speedMetersPerSecond > 0.0) {
                pathMeters / mission.constraints.speedMetersPerSecond
            } else 0.0,
        )
        return DjiWaylineSegment(waylineId, first, last, segmentMission)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val north = (lat2 - lat1) * 111_132.0
        val east = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
        return hypot(north, east)
    }
}
