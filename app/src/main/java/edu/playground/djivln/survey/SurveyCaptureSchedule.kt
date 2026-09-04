package edu.playground.djivln.survey

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

data class SurveyCaptureEvent(
    val captureIndex: Int,
    val point: GeoPoint,
    val headingDegrees: Double,
    val gimbalPitchDegrees: Double,
    val passIndex: Int,
    val captureView: SurveyCaptureView,
    val distanceAlongPassMeters: Double,
    val passLengthMeters: Double,
    val estimatedMissionDistanceMeters: Double,
)

/** Planned camera events matching the executor's distance-trigger pass semantics. */
object SurveyCaptureSchedule {
    fun build(mission: SurveyMission): List<SurveyCaptureEvent> {
        val result = mutableListOf<SurveyCaptureEvent>()
        var missionDistance = 0.0
        var previousPassEnd: GeoPoint? = null
        mission.surveyPasses().forEach { pass ->
            val start = pass.start
            previousPassEnd?.let { missionDistance += distanceMeters(it, start.point) }
            if (pass.isTransitOnly) {
                missionDistance += pass.waypoints.zipWithNext().sumOf { (a, b) ->
                    distanceMeters(a.point, b.point)
                }
                previousPassEnd = pass.end.point
                return@forEach
            }
            if (pass.isPointCapture) {
                result += SurveyCaptureEvent(
                    captureIndex = result.size,
                    point = start.point,
                    headingDegrees = start.headingDegrees,
                    gimbalPitchDegrees = start.gimbalPitchDegrees,
                    passIndex = start.passIndex,
                    captureView = start.captureView,
                    distanceAlongPassMeters = 0.0,
                    passLengthMeters = 0.0,
                    estimatedMissionDistanceMeters = missionDistance,
                )
                previousPassEnd = start.point
                return@forEach
            }
            val end = pass.end
            val segments = pass.waypoints.zipWithNext().map { (a, b) ->
                distanceMeters(a.point, b.point)
            }
            val passLength = segments.sum()
            val interval = requireNotNull(start.captureIntervalMeters)
            val captureCount = maxOf(2, ceil(passLength / interval).toInt() + 1)
            repeat(captureCount) { sampleIndex ->
                val ratio = sampleIndex.toDouble() / (captureCount - 1)
                val along = ratio * passLength
                val pose = poseAlong(pass.waypoints, segments, along)
                result += SurveyCaptureEvent(
                    captureIndex = result.size,
                    point = pose.point,
                    headingDegrees = pose.headingDegrees,
                    gimbalPitchDegrees = pose.gimbalPitchDegrees,
                    passIndex = start.passIndex,
                    captureView = start.captureView,
                    distanceAlongPassMeters = along,
                    passLengthMeters = passLength,
                    estimatedMissionDistanceMeters = missionDistance + along,
                )
            }
            missionDistance += passLength
            previousPassEnd = end.point
        }
        check(result.size == mission.estimatedPhotoCount) {
            "capture schedule ${result.size} does not match planner estimate ${mission.estimatedPhotoCount}"
        }
        return result
    }

    private data class CapturePose(
        val point: GeoPoint,
        val headingDegrees: Double,
        val gimbalPitchDegrees: Double,
    )

    private fun poseAlong(
        waypoints: List<SurveyWaypoint>,
        segmentLengths: List<Double>,
        distance: Double,
    ): CapturePose {
        var remaining = distance
        segmentLengths.forEachIndexed { index, length ->
            if (remaining <= length || index == segmentLengths.lastIndex) {
                val ratio = if (length <= 1e-9) 0.0 else (remaining / length).coerceIn(0.0, 1.0)
                val start = waypoints[index]
                val end = waypoints[index + 1]
                return CapturePose(
                    point = interpolate(start.point, end.point, ratio),
                    headingDegrees = interpolateHeading(
                        start.headingDegrees,
                        end.headingDegrees,
                        ratio,
                    ),
                    gimbalPitchDegrees = start.gimbalPitchDegrees +
                        (end.gimbalPitchDegrees - start.gimbalPitchDegrees) * ratio,
                )
            }
            remaining -= length
        }
        val last = waypoints.last()
        return CapturePose(last.point, last.headingDegrees, last.gimbalPitchDegrees)
    }

    private fun interpolate(a: GeoPoint, b: GeoPoint, ratio: Double) = GeoPoint(
        latitude = a.latitude + (b.latitude - a.latitude) * ratio,
        longitude = a.longitude + (b.longitude - a.longitude) * ratio,
        altitudeMeters = a.altitudeMeters + (b.altitudeMeters - a.altitudeMeters) * ratio,
    )

    private fun interpolateHeading(start: Double, end: Double, ratio: Double): Double {
        val delta = (end - start + 540.0) % 360.0 - 180.0
        return (start + delta * ratio + 360.0) % 360.0
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val meanLatitude = (a.latitude + b.latitude) / 2.0
        val north = (b.latitude - a.latitude) * 111_132.0
        val east = (b.longitude - a.longitude) * 111_320.0 * cos(Math.toRadians(meanLatitude))
        return hypot(north, east)
    }
}
