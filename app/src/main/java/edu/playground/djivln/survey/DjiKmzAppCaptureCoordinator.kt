package edu.playground.djivln.survey

import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Drives camera captures from Android while DJI's KMZ executor remains responsible for flight.
 * This avoids consumer-aircraft KMZ photo actions silently dropping triggers while the camera is busy.
 */
class DjiKmzAppCaptureCoordinator(
    private val proximityMeters: Double = 4.0,
    minimumCapturePeriodMillis: Long = 1_200L,
) {
    data class Request(
        val position: GeoPoint,
        val passIndex: Int,
        val captureView: SurveyCaptureView,
        val reason: String,
        val waypointIndex: Int = 0,
    )

    data class GimbalTarget(
        val passIndex: Int,
        val captureView: SurveyCaptureView,
        val pitchDegrees: Double,
        val waypointIndex: Int = 0,
        val isPointCapture: Boolean = false,
    ) {
        fun requiresStoppedPose(continuousCaptureIndices: Set<Int>): Boolean =
            isPointCapture && waypointIndex !in continuousCaptureIndices
    }

    data class Progress(
        val passIndex: Int,
        val firstWaypointIndex: Int,
        val lastWaypointIndex: Int,
        val captureActive: Boolean,
        val recovering: Boolean,
        val transitOnly: Boolean,
    )

    private val distanceCapture = SurveyDistanceCaptureController(minimumCapturePeriodMillis)
    private var mission: SurveyMission? = null
    private var passes: List<SurveyPassWaypoints> = emptyList()
    private var passCursor = 0
    private var passStarted = false
    private var pendingAdvance = false
    private var pendingRequest: Request? = null
    private var recoveryEntryPoint: GeoPoint? = null
    private var continuousCaptureIndices: Set<Int> = emptySet()
    private val missedPointPasses = mutableSetOf<Int>()
    private val unreportedMisses = mutableSetOf<Int>()

    val active: Boolean get() = mission != null

    fun isPending(request: Request): Boolean = pendingRequest === request

    fun progress(waypointIndex: Int?): Progress? {
        advancePastCompletedPasses(waypointIndex)
        val pass = passes.getOrNull(passCursor) ?: return null
        return Progress(
            passIndex = pass.start.passIndex,
            firstWaypointIndex = pass.firstWaypointIndex,
            lastWaypointIndex = pass.lastWaypointIndex,
            captureActive = passStarted && recoveryEntryPoint == null && !pass.isTransitOnly,
            recovering = recoveryEntryPoint != null,
            transitOnly = pass.isTransitOnly,
        )
    }

    fun arm(mission: SurveyMission, continuousCaptureIndices: Set<Int> = emptySet()) {
        this.mission = mission
        this.continuousCaptureIndices = continuousCaptureIndices
        missedPointPasses.clear()
        unreportedMisses.clear()
        passes = mission.surveyPasses()
        passCursor = 0
        passStarted = false
        pendingAdvance = false
        pendingRequest = null
        recoveryEntryPoint = null
        distanceCapture.configure(
            mission.constraints.captureTriggerMode,
            mission.constraints.timedCaptureIntervalSeconds,
        )
    }

    fun armFromBreakpoint(mission: SurveyMission, breakpoint: WaylineBreakpoint, continuousCaptureIndices: Set<Int> = emptySet()) {
        arm(mission, continuousCaptureIndices)
        while (passCursor < passes.size && breakpoint.waypointId > passes[passCursor].lastWaypointIndex) {
            advancePass()
        }
        val pass = passes.getOrNull(passCursor) ?: return
        if (pass.isTransitOnly || pass.isPointCapture) return
        if (breakpoint.waypointId !in pass.firstWaypointIndex..pass.lastWaypointIndex) return
        val recoveryPosition = breakpointPosition(mission, breakpoint) ?: return
        // executeFromBreakpoint first flies from the aircraft's current position back to the
        // breakpoint. Do not arm interval capture until that recovery transit has actually ended.
        recoveryEntryPoint = recoveryPosition
    }

    fun stop() {
        mission = null
        passes = emptyList()
        passCursor = 0
        passStarted = false
        pendingAdvance = false
        pendingRequest = null
        recoveryEntryPoint = null
        distanceCapture.reset()
        continuousCaptureIndices = emptySet()
        missedPointPasses.clear()
        unreportedMisses.clear()
    }

    /**
     * Returns the active capture unit's required pitch, including while DJI is flying back to a
     * breakpoint. Breakpoint execution can skip the pass-start action that originally set this
     * pitch, so the Android capture path must verify it independently before taking a photo.
     */
    fun currentGimbalTarget(waypointIndex: Int?): GimbalTarget? {
        advancePastCompletedPasses(waypointIndex)
        val pass = passes.getOrNull(passCursor) ?: return null
        if (pass.isTransitOnly) return null
        return GimbalTarget(
            passIndex = pass.start.passIndex,
            captureView = pass.start.captureView,
            pitchDegrees = pass.start.gimbalPitchDegrees,
            waypointIndex = pass.firstWaypointIndex,
            isPointCapture = pass.isPointCapture,
        )
    }

    fun tick(
        position: GeoPoint,
        waypointIndex: Int?,
        nowElapsedMillis: Long,
        cameraReady: Boolean,
        horizontalSpeedMetersPerSecond: Double,
    ): Request? {
        if (mission == null || pendingRequest != null) return null
        recoveryEntryPoint?.let { recoveryPosition ->
            if (distanceMeters(position, recoveryPosition) > proximityMeters) return null
            val recoveryPass = passes.getOrNull(passCursor) ?: return null
            recoveryEntryPoint = null
            if (!recoveryPass.isTransitOnly && !recoveryPass.isPointCapture) {
                passStarted = true
                // Seed the interval at the restored position without taking a photo there. The
                // first request is emitted only after the aircraft moves one capture interval.
                distanceCapture.resumeIntervalFrom(
                    recoveryPass.start,
                    recoveryPosition,
                    nowElapsedMillis,
                )
            }
            return null
        }
        advancePastCompletedPasses(waypointIndex)
        val pass = passes.getOrNull(passCursor) ?: return null
        if (pass.isTransitOnly) {
            val reachedEnd = distanceMeters(position, pass.end.point) <= proximityMeters ||
                waypointIndex?.let { it >= pass.lastWaypointIndex } == true
            if (reachedEnd) advancePass()
            return null
        }
        if (pass.isPointCapture) {
            if (!cameraReady || !hasReached(pass.start.point, position, waypointIndex, pass.firstWaypointIndex)) return null
            if (pass.firstWaypointIndex in continuousCaptureIndices &&
                (distanceMeters(pass.start.point, position) > 2.0 ||
                    kotlin.math.abs(pass.start.point.altitudeMeters - position.altitudeMeters) > 2.0)
            ) return null
            if (!distanceCapture.onWaypointReached(pass.start, position, nowElapsedMillis, true)) return null
            pendingAdvance = true
            return request(pass, position, "point_capture")
        }
        if (!passStarted) {
            if (!hasReached(pass.start.point, position, waypointIndex, pass.firstWaypointIndex)) return null
            passStarted = true
            val shouldCapture = distanceCapture.onWaypointReached(
                pass.start,
                position,
                nowElapsedMillis,
                cameraReady,
            )
            if (shouldCapture) return request(pass, position, "pass_start")
        }
        val reachedEnd = distanceMeters(position, pass.end.point) <= proximityMeters ||
            waypointIndex?.let { it > pass.lastWaypointIndex } == true
        if (reachedEnd) {
            val shouldCapture = distanceCapture.onWaypointReached(
                pass.end,
                position,
                nowElapsedMillis,
                cameraReady,
            )
            if (shouldCapture) {
                pendingAdvance = true
                return request(pass, position, "pass_end")
            }
            if (!distanceCapture.active) advancePass()
            return null
        }
        if (distanceCapture.onPosition(
                position,
                nowElapsedMillis,
                cameraReady,
                horizontalSpeedMetersPerSecond,
            )
        ) return request(pass, position, "distance_interval")
        return null
    }

    fun onCaptureResult(position: GeoPoint, nowElapsedMillis: Long, success: Boolean, expectedRequest: Request? = null) {
        val request = pendingRequest ?: return
        if (expectedRequest != null && expectedRequest !== request) return
        val samePass = passes.getOrNull(passCursor)?.start?.passIndex == request.passIndex
        if (samePass) distanceCapture.onCaptureResult(position, nowElapsedMillis, success)
        pendingRequest = null
        if (!success && mission?.recaptureFlightMode == RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL) {
            recordMiss(request.passIndex)
        }
        if (pendingAdvance && samePass) advancePass()
    }

    fun cancelPendingCapture() {
        if (mission?.recaptureFlightMode == RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL) {
            pendingRequest?.let { recordMiss(it.passIndex) }
        }
        pendingRequest = null
        pendingAdvance = false
        distanceCapture.cancelPendingCapture()
    }

    private fun request(pass: SurveyPassWaypoints, position: GeoPoint, reason: String): Request = Request(
        position = position,
        passIndex = pass.start.passIndex,
        captureView = pass.start.captureView,
        reason = reason,
        waypointIndex = pass.firstWaypointIndex,
    ).also { pendingRequest = it }

    private fun advancePass() {
        passCursor += 1
        passStarted = false
        pendingAdvance = false
        distanceCapture.reset()
        mission?.let {
            distanceCapture.configure(it.constraints.captureTriggerMode, it.constraints.timedCaptureIntervalSeconds)
        }
    }

    private fun advancePastCompletedPasses(waypointIndex: Int?) {
        while (passCursor < passes.size && waypointIndex != null && waypointIndex > passes[passCursor].lastWaypointIndex) {
            val pass = passes[passCursor]
            if (mission?.recaptureFlightMode == RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL &&
                pass.isPointCapture && pendingRequest?.passIndex != pass.start.passIndex
            ) recordMiss(pass.start.passIndex)
            advancePass()
        }
    }

    fun drainMissedPointPasses(): List<Int> = unreportedMisses.toList().also { unreportedMisses.clear() }

    fun finish() {
        if (mission?.recaptureFlightMode != RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL) return
        pendingRequest?.let { recordMiss(it.passIndex) }
        passes.drop(passCursor).filter { it.isPointCapture }.forEach { recordMiss(it.start.passIndex) }
    }

    private fun recordMiss(passIndex: Int) {
        if (missedPointPasses.add(passIndex)) unreportedMisses.add(passIndex)
    }

    private fun hasReached(
        target: GeoPoint,
        position: GeoPoint,
        waypointIndex: Int?,
        targetIndex: Int,
    ): Boolean {
        // DJI can advance currentWaypointIndex while travelling to the first planned point.
        // Never treat that index alone as a camera trigger: the actual position must reach the
        // capture pass, otherwise an approach leg produces an unwanted first photo.
        return distanceMeters(position, target) <= proximityMeters
    }

    private fun breakpointPosition(mission: SurveyMission, breakpoint: WaylineBreakpoint): GeoPoint? {
        if (breakpoint.latitude != null && breakpoint.longitude != null) {
            return GeoPoint(
                breakpoint.latitude,
                breakpoint.longitude,
                breakpoint.altitudeMeters ?: mission.waypoints.getOrNull(breakpoint.waypointId)?.point?.altitudeMeters
                    ?: 0.0,
            )
        }
        val start = mission.waypoints.getOrNull(breakpoint.waypointId)?.point ?: return null
        val end = mission.waypoints.getOrNull(breakpoint.waypointId + 1)?.point ?: return start
        val ratio = breakpoint.segmentProgress.coerceIn(0.0, 1.0)
        return GeoPoint(
            latitude = start.latitude + (end.latitude - start.latitude) * ratio,
            longitude = start.longitude + (end.longitude - start.longitude) * ratio,
            altitudeMeters = start.altitudeMeters + (end.altitudeMeters - start.altitudeMeters) * ratio,
        )
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val north = (b.latitude - a.latitude) * 111_132.0
        val east = (b.longitude - a.longitude) * 111_320.0 *
            cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        return hypot(north, east)
    }
}
