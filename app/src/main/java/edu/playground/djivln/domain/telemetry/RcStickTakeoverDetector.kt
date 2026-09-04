package edu.playground.djivln.domain.telemetry

import kotlin.math.abs

class RcStickTakeoverDetector(
    private val engageThreshold: Int = 100,
    private val releaseThreshold: Int = 50,
    private val engageHoldNanos: Long = 250_000_000L,
) {
    private var active = false
    private var candidateSinceNanos = 0L

    fun update(positions: IntArray, nowNanos: Long): Boolean {
        val maximum = positions.maxOfOrNull { abs(it) } ?: 0
        if (active) {
            if (maximum <= releaseThreshold) {
                active = false
                candidateSinceNanos = 0L
            }
            return active
        }
        if (maximum < engageThreshold) {
            candidateSinceNanos = 0L
            return false
        }
        if (candidateSinceNanos == 0L) candidateSinceNanos = nowNanos
        if (nowNanos - candidateSinceNanos >= engageHoldNanos) {
            active = true
            candidateSinceNanos = 0L
        }
        return active
    }

    fun reset() {
        active = false
        candidateSinceNanos = 0L
    }
}
