package edu.playground.djivln.survey

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

enum class SurveyReplayState {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
}

data class SurveyReplaySnapshot(
    val state: SurveyReplayState,
    val point: GeoPoint?,
    val headingDegrees: Double,
    val passIndex: Int,
    val sampleIndex: Int,
    val totalSamples: Int,
    val progress: Double,
    val captureActive: Boolean,
)

/**
 * Deterministic, side-effect-free mission replay used by the Android preview UI.
 * It never sends aircraft, gimbal, or camera commands.
 */
class SurveyMissionReplay(
    val mission: SurveyMission,
    sampleSpacingMeters: Double = 2.0,
) {
    private data class Sample(
        val point: GeoPoint,
        val headingDegrees: Double,
        val passIndex: Int,
        val captureActive: Boolean,
    )

    private val samples: List<Sample>
    private var cursor = 0
    var state: SurveyReplayState = SurveyReplayState.IDLE
        private set

    init {
        require(sampleSpacingMeters in 0.2..100.0) { "replay spacing must be in [0.2, 100] meters" }
        samples = buildSamples(mission, sampleSpacingMeters)
        require(samples.isNotEmpty()) { "mission has no replayable waypoints" }
    }

    fun start(): SurveyReplaySnapshot {
        if (state == SurveyReplayState.COMPLETED) cursor = 0
        state = SurveyReplayState.RUNNING
        return currentSnapshot()
    }

    fun pause(): SurveyReplaySnapshot {
        if (state == SurveyReplayState.RUNNING) state = SurveyReplayState.PAUSED
        return currentSnapshot()
    }

    fun resume(): SurveyReplaySnapshot {
        if (state == SurveyReplayState.PAUSED) state = SurveyReplayState.RUNNING
        return currentSnapshot()
    }

    fun stop(): SurveyReplaySnapshot {
        cursor = 0
        state = SurveyReplayState.IDLE
        return currentSnapshot()
    }

    fun advance(): SurveyReplaySnapshot {
        if (state != SurveyReplayState.RUNNING) return currentSnapshot()
        if (cursor < samples.lastIndex) {
            cursor++
        } else {
            state = SurveyReplayState.COMPLETED
        }
        return currentSnapshot()
    }

    fun snapshot(): SurveyReplaySnapshot = currentSnapshot()

    private fun currentSnapshot(): SurveyReplaySnapshot {
        val sample = samples[cursor]
        return SurveyReplaySnapshot(
            state = state,
            point = sample.point,
            headingDegrees = sample.headingDegrees,
            passIndex = sample.passIndex,
            sampleIndex = cursor,
            totalSamples = samples.size,
            progress = if (samples.size <= 1) 1.0 else cursor.toDouble() / samples.lastIndex,
            captureActive = sample.captureActive,
        )
    }

    private fun buildSamples(mission: SurveyMission, spacingMeters: Double): List<Sample> {
        val waypoints = mission.waypoints
        if (waypoints.isEmpty()) return emptyList()
        val result = mutableListOf<Sample>()
        var captureActive = waypoints.first().captureAction == CaptureAction.START_DISTANCE_INTERVAL
        result += Sample(
            waypoints.first().point,
            waypoints.first().headingDegrees,
            waypoints.first().passIndex,
            captureActive,
        )
        for (index in 1 until waypoints.size) {
            val previous = waypoints[index - 1]
            val waypoint = waypoints[index]
            val segmentMeters = distanceMeters(previous.point, waypoint.point)
            val steps = maxOf(1, ceil(segmentMeters / spacingMeters).toInt())
            for (step in 1..steps) {
                val ratio = step.toDouble() / steps
                result += Sample(
                    point = interpolate(previous.point, waypoint.point, ratio),
                    headingDegrees = segmentHeading(previous.point, waypoint.point),
                    passIndex = waypoint.passIndex,
                    captureActive = captureActive,
                )
            }
            when (waypoint.captureAction) {
                CaptureAction.START_DISTANCE_INTERVAL -> captureActive = true
                CaptureAction.STOP_DISTANCE_INTERVAL -> captureActive = false
                CaptureAction.CAPTURE_ON_REACH -> Unit
                CaptureAction.NONE -> Unit
            }
        }
        return result
    }

    private fun interpolate(a: GeoPoint, b: GeoPoint, ratio: Double) = GeoPoint(
        latitude = a.latitude + (b.latitude - a.latitude) * ratio,
        longitude = a.longitude + (b.longitude - a.longitude) * ratio,
        altitudeMeters = a.altitudeMeters + (b.altitudeMeters - a.altitudeMeters) * ratio,
    )

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val meanLatitude = (a.latitude + b.latitude) / 2.0
        val north = (b.latitude - a.latitude) * 111_132.0
        val east = (b.longitude - a.longitude) * 111_320.0 * cos(Math.toRadians(meanLatitude))
        return hypot(north, east)
    }

    private fun segmentHeading(a: GeoPoint, b: GeoPoint): Double {
        val meanLatitude = (a.latitude + b.latitude) / 2.0
        val north = (b.latitude - a.latitude) * 111_132.0
        val east = (b.longitude - a.longitude) * 111_320.0 * cos(Math.toRadians(meanLatitude))
        val value = Math.toDegrees(kotlin.math.atan2(east, north)) % 360.0
        return if (value < 0.0) value + 360.0 else value
    }
}
