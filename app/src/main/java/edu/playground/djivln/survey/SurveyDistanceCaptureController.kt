package edu.playground.djivln.survey

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

class SurveyDistanceCaptureController @JvmOverloads constructor(
    private val minimumCapturePeriodMillis: Long = 1_200L,
    initialCaptureLatencyMillis: Long = DEFAULT_CAPTURE_LATENCY_MILLIS,
) {
    var active: Boolean = false
        private set
    var estimatedCaptureLatencyMillis: Long = initialCaptureLatencyMillis.coerceIn(
        MIN_CAPTURE_LATENCY_MILLIS,
        MAX_CAPTURE_LATENCY_MILLIS,
    )
        private set
    private var intervalMeters: Double = Double.NaN
    private var lastCapturePoint: GeoPoint? = null
    private var lastCaptureElapsedMillis: Long = Long.MIN_VALUE
    private var lastCaptureRequestElapsedMillis: Long = Long.MIN_VALUE
    private var pendingCaptureElapsedMillis: Long = Long.MIN_VALUE
    private var stopAfterPendingCapture: Boolean = false
    private var triggerMode: SurveyCaptureTriggerMode = SurveyCaptureTriggerMode.DISTANCE
    private var timedIntervalMillis: Long = 1_000L

    fun configure(mode: SurveyCaptureTriggerMode, timedCaptureIntervalSeconds: Double) {
        require(timedCaptureIntervalSeconds > 0.0 && timedCaptureIntervalSeconds.isFinite())
        reset()
        triggerMode = mode
        timedIntervalMillis = (timedCaptureIntervalSeconds * 1_000.0).toLong()
    }

    fun onWaypointReached(
        waypoint: SurveyWaypoint,
        position: GeoPoint,
        nowElapsedMillis: Long,
        cameraReady: Boolean,
    ): Boolean = when (waypoint.captureAction) {
        CaptureAction.START_DISTANCE_INTERVAL -> {
            active = true
            intervalMeters = requireNotNull(waypoint.captureIntervalMeters)
            captureIfReady(
                position,
                nowElapsedMillis,
                cameraReady,
                horizontalSpeedMetersPerSecond = 0.0,
                force = true,
                stopAfterCapture = false,
            )
        }
        CaptureAction.STOP_DISTANCE_INTERVAL -> {
            val shouldCaptureEnd = active && when (triggerMode) {
                SurveyCaptureTriggerMode.DISTANCE -> lastCapturePoint?.let {
                    distanceMeters(it, position) >= MIN_DISTINCT_END_FRAME_METERS
                } == true
                SurveyCaptureTriggerMode.TIME -> lastCaptureElapsedMillis != Long.MIN_VALUE &&
                    nowElapsedMillis - lastCaptureElapsedMillis + estimatedCaptureLatencyMillis >=
                    timedIntervalMillis / 2
            }
            if (!shouldCaptureEnd) {
                active = false
                false
            } else {
                captureIfReady(
                    position,
                    nowElapsedMillis,
                    cameraReady,
                    horizontalSpeedMetersPerSecond = 0.0,
                    force = true,
                    stopAfterCapture = true,
                )
            }
        }
        CaptureAction.CAPTURE_ON_REACH -> captureIfReady(
            position,
            nowElapsedMillis,
            cameraReady,
            horizontalSpeedMetersPerSecond = 0.0,
            force = true,
            stopAfterCapture = false,
        )
        CaptureAction.NONE -> false
    }

    /** Resume an interval at an already-covered position without requesting a duplicate frame. */
    fun resumeIntervalFrom(
        waypoint: SurveyWaypoint,
        position: GeoPoint,
        nowElapsedMillis: Long,
    ) {
        require(waypoint.captureAction == CaptureAction.START_DISTANCE_INTERVAL)
        active = true
        intervalMeters = requireNotNull(waypoint.captureIntervalMeters)
        lastCapturePoint = position
        lastCaptureElapsedMillis = nowElapsedMillis
        lastCaptureRequestElapsedMillis = nowElapsedMillis
        pendingCaptureElapsedMillis = Long.MIN_VALUE
        stopAfterPendingCapture = false
    }

    @JvmOverloads
    fun onPosition(
        position: GeoPoint,
        nowElapsedMillis: Long,
        cameraReady: Boolean,
        horizontalSpeedMetersPerSecond: Double = 0.0,
    ): Boolean {
        if (!active || !cameraReady) return false
        return captureIfReady(
            position,
            nowElapsedMillis,
            true,
            horizontalSpeedMetersPerSecond,
            force = lastCapturePoint == null,
            stopAfterCapture = false,
        )
    }

    fun onCaptureResult(position: GeoPoint, nowElapsedMillis: Long, success: Boolean) {
        val requestElapsedMillis = pendingCaptureElapsedMillis
        if (requestElapsedMillis == Long.MIN_VALUE) return
        if (success) {
            val observedLatencyMillis = (nowElapsedMillis - requestElapsedMillis).coerceIn(
                MIN_CAPTURE_LATENCY_MILLIS,
                MAX_CAPTURE_LATENCY_MILLIS,
            )
            estimatedCaptureLatencyMillis = (
                estimatedCaptureLatencyMillis * (1.0 - CAPTURE_LATENCY_EWMA_ALPHA) +
                    observedLatencyMillis * CAPTURE_LATENCY_EWMA_ALPHA
                ).toLong()
            lastCapturePoint = position
            lastCaptureElapsedMillis = nowElapsedMillis
        }
        pendingCaptureElapsedMillis = Long.MIN_VALUE
        if (stopAfterPendingCapture) active = false
        stopAfterPendingCapture = false
    }

    fun cancelPendingCapture() {
        pendingCaptureElapsedMillis = Long.MIN_VALUE
        stopAfterPendingCapture = false
    }

    fun compensationLeadMeters(horizontalSpeedMetersPerSecond: Double): Double {
        if (!horizontalSpeedMetersPerSecond.isFinite() || horizontalSpeedMetersPerSecond <= 0.0 ||
            !intervalMeters.isFinite()
        ) return 0.0
        return min(
            intervalMeters * MAX_CAPTURE_LEAD_FRACTION,
            horizontalSpeedMetersPerSecond * estimatedCaptureLatencyMillis / 1_000.0,
        )
    }

    fun reset() {
        active = false
        intervalMeters = Double.NaN
        lastCapturePoint = null
        lastCaptureElapsedMillis = Long.MIN_VALUE
        lastCaptureRequestElapsedMillis = Long.MIN_VALUE
        cancelPendingCapture()
    }

    fun restoreActive(
        captureIntervalMeters: Double,
        mode: SurveyCaptureTriggerMode = SurveyCaptureTriggerMode.DISTANCE,
        timedCaptureIntervalSeconds: Double = 1.0,
    ) {
        require(captureIntervalMeters > 0.0 && captureIntervalMeters.isFinite())
        configure(mode, timedCaptureIntervalSeconds)
        active = true
        intervalMeters = captureIntervalMeters
    }

    private fun captureIfReady(
        position: GeoPoint,
        nowElapsedMillis: Long,
        cameraReady: Boolean,
        horizontalSpeedMetersPerSecond: Double,
        force: Boolean,
        stopAfterCapture: Boolean,
    ): Boolean {
        if (!cameraReady || pendingCaptureElapsedMillis != Long.MIN_VALUE) return false
        val enoughTime = lastCaptureRequestElapsedMillis == Long.MIN_VALUE ||
            nowElapsedMillis - lastCaptureRequestElapsedMillis >= minimumCapturePeriodMillis
        val enoughTrigger = force || when (triggerMode) {
            SurveyCaptureTriggerMode.DISTANCE -> lastCapturePoint?.let {
                distanceMeters(it, position) + compensationLeadMeters(horizontalSpeedMetersPerSecond) >=
                    intervalMeters
            } ?: true
            SurveyCaptureTriggerMode.TIME -> lastCaptureElapsedMillis == Long.MIN_VALUE ||
                nowElapsedMillis - lastCaptureElapsedMillis + estimatedCaptureLatencyMillis >=
                timedIntervalMillis
        }
        if (!enoughTime || !enoughTrigger) return false
        pendingCaptureElapsedMillis = nowElapsedMillis
        lastCaptureRequestElapsedMillis = nowElapsedMillis
        stopAfterPendingCapture = stopAfterCapture
        return true
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val north = (b.latitude - a.latitude) * 111_132.0
        val east = (b.longitude - a.longitude) * 111_320.0 *
            cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        return hypot(north, east)
    }

    private companion object {
        const val MIN_DISTINCT_END_FRAME_METERS = 0.5
        const val DEFAULT_CAPTURE_LATENCY_MILLIS = 280L
        const val MIN_CAPTURE_LATENCY_MILLIS = 50L
        const val MAX_CAPTURE_LATENCY_MILLIS = 1_000L
        const val CAPTURE_LATENCY_EWMA_ALPHA = 0.25
        const val MAX_CAPTURE_LEAD_FRACTION = 0.4
    }
}
