package edu.playground.djivln.survey

import edu.playground.djivln.domain.telemetry.AircraftSnapshot

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
        if (target == null || !gimbalVerified || !ContinuousCapturePosePolicy.ready(aircraft, target, nowNanos)) {
            stableSinceNanos = 0L
            return false
        }
        if (stableSinceNanos == 0L) stableSinceNanos = nowNanos
        return nowNanos - stableSinceNanos >= 800_000_000L
    }
}
