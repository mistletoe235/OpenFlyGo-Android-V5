package edu.playground.djivln.survey

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import kotlin.math.abs

class MovingCapturePoseGate {
    private var target: SurveyWaypoint? = null
    private var stableSinceNanos = 0L
    private var lastCheckNanos = 0L

    fun reset() {
        target = null
        stableSinceNanos = 0L
        lastCheckNanos = 0L
    }

    fun ready(
        aircraft: AircraftSnapshot,
        target: SurveyWaypoint?,
        gimbalVerified: Boolean,
        nowNanos: Long,
    ): Boolean {
        if (this.target != target || nowNanos < lastCheckNanos ||
            nowNanos - lastCheckNanos > 1_000_000_000L
        ) stableSinceNanos = 0L
        this.target = target
        lastCheckNanos = nowNanos
        if (target == null || !gimbalVerified || !aligned(aircraft, target)) {
            stableSinceNanos = 0L
            return false
        }
        if (stableSinceNanos == 0L) stableSinceNanos = nowNanos
        return nowNanos - stableSinceNanos >= 800_000_000L
    }

    private fun aligned(aircraft: AircraftSnapshot, target: SurveyWaypoint): Boolean {
        val heading = aircraft.headingDegrees ?: return false
        val pitch = aircraft.gimbalPitchDegrees ?: return false
        val altitude = aircraft.relativeAltitudeMeters ?: return false
        return aircraft.connected && heading.isFinite() && pitch.isFinite() && altitude.isFinite() &&
            abs(((heading - target.headingDegrees) % 360.0 + 540.0) % 360.0 - 180.0) <= 3.0 &&
            SurveyGimbalSettlePolicy.isSettled(target.gimbalPitchDegrees, pitch) &&
            abs(altitude - target.point.altitudeMeters) <= 2.0
    }
}
