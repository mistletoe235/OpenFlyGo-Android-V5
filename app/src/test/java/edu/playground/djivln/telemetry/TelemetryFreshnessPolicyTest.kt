package edu.playground.djivln.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryFreshnessPolicyTest {
    private fun valid(now: Long = 10_000L) = FlightTelemetryFreshnessInput(
        nowMs = now,
        connected = true,
        altitudePushAtMs = now - 100,
        velocityPushAtMs = now - 100,
        positionPushAtMs = now - 100,
        headingPushAtMs = now - 100,
        altitudeMeters = 10.0,
        horizontalSpeedMetersPerSecond = 0.2,
        headingDegrees = 90.0,
        latitude = 31.2,
        longitude = 121.5
    )

    @Test fun acceptsFreshPushTelemetry() = assertTrue(TelemetryFreshnessPolicy().isFresh(valid()))

    @Test fun rejectsCachedOnlyOrStaleTelemetry() {
        assertFalse(TelemetryFreshnessPolicy().isFresh(valid().copy(altitudePushAtMs = null)))
        assertFalse(TelemetryFreshnessPolicy().isFresh(valid().copy(positionPushAtMs = 1_000L)))
    }

    @Test fun rejectsDisconnectAndZeroCoordinate() {
        assertFalse(TelemetryFreshnessPolicy().isFresh(valid().copy(connected = false)))
        assertFalse(TelemetryFreshnessPolicy().isFresh(valid().copy(latitude = 0.0, longitude = 0.0)))
    }
}
