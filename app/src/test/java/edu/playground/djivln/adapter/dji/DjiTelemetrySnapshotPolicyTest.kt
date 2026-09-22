package edu.playground.djivln.adapter.dji

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.telemetry.GeoPoint
import edu.playground.djivln.domain.telemetry.VelocityMetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DjiTelemetrySnapshotPolicyTest {
    private val pushedVelocity = VelocityMetersPerSecond(0.3, -0.2, 0.1)
    private val cachedVelocity = VelocityMetersPerSecond(1.0, 2.0, 3.0)

    @Test
    fun rebindClearsVelocityAndAllGimbalValuesWithoutResettingUnrelatedState() {
        val previous = AircraftSnapshot(
            connected = true,
            aircraftLocation = GeoPoint(31.2, 121.5),
            aircraftBatteryPercent = 80,
            velocity = pushedVelocity,
            velocityUpdatedAtNanos = 100L,
            gimbalPitchDegrees = -45.0,
            gimbalRollDegrees = 1.0,
            gimbalYawDegrees = 90.0,
            gimbalYawRelativeToAircraftHeadingDegrees = 20.0,
            gimbalAttitudeUpdatedAtNanos = 101L,
            gimbalYawRelativeUpdatedAtNanos = 102L,
            flightStateUpdatedAtNanos = 103L,
            unsupportedFields = setOf("velocity"),
        )

        val rebound = DjiTelemetrySnapshotPolicy.rebind(previous)

        assertEquals(previous.copy(
            velocity = null,
            velocityUpdatedAtNanos = 0L,
            gimbalPitchDegrees = null,
            gimbalRollDegrees = null,
            gimbalYawDegrees = null,
            gimbalYawRelativeToAircraftHeadingDegrees = null,
            gimbalAttitudeUpdatedAtNanos = 0L,
            gimbalYawRelativeUpdatedAtNanos = 0L,
            unsupportedFields = emptySet(),
        ), rebound)
    }

    @Test
    fun cacheCanSeedDisplayButCannotClaimFreshVelocityOrFlightState() {
        val previous = AircraftSnapshot(flightStateUpdatedAtNanos = 80L, updatedAtNanos = 90L)

        val seeded = DjiTelemetrySnapshotPolicy.cachedVelocity(previous, cachedVelocity)

        assertEquals(previous.copy(velocity = cachedVelocity), seeded)
        assertEquals(0L, seeded.velocityUpdatedAtNanos)
    }

    @Test
    fun cacheCannotReplaceAReceivedVelocityEvenWhenItsTimestampIsOld() {
        val previous = AircraftSnapshot(velocity = pushedVelocity, velocityUpdatedAtNanos = 1L)

        assertSame(previous, DjiTelemetrySnapshotPolicy.cachedVelocity(previous, cachedVelocity))
    }

    @Test
    fun cacheCannotResurrectVelocityInvalidatedByAPush() {
        val invalidated = DjiTelemetrySnapshotPolicy.velocityPush(AircraftSnapshot(), null, 200L)

        assertSame(invalidated, DjiTelemetrySnapshotPolicy.cachedVelocity(invalidated, cachedVelocity))
        assertNull(invalidated.velocity)
    }

    @Test
    fun invalidCachedVelocityDoesNotRefreshTimestampsOrOverwriteAPush() {
        val initial = AircraftSnapshot(flightStateUpdatedAtNanos = 80L)
        assertEquals(initial, DjiTelemetrySnapshotPolicy.cachedVelocity(initial, null))
        val received = DjiTelemetrySnapshotPolicy.velocityPush(initial, pushedVelocity, 200L)
        assertSame(received, DjiTelemetrySnapshotPolicy.cachedVelocity(received, null))
    }

    @Test
    fun pushUsesOneReceiptTimestampForVelocityAndFlightState() {
        val previous = AircraftSnapshot(velocity = cachedVelocity, updatedAtNanos = 90L)

        val received = DjiTelemetrySnapshotPolicy.velocityPush(previous, pushedVelocity, 200L)

        assertEquals(previous.copy(
            velocity = pushedVelocity,
            velocityUpdatedAtNanos = 200L,
            flightStateUpdatedAtNanos = 200L,
        ), received)
    }

    @Test
    fun reconnectWaitsForAPushRatherThanRefreshingCacheTimestamps() {
        val previous = AircraftSnapshot(velocity = pushedVelocity, velocityUpdatedAtNanos = 100L)
        val rebound = DjiTelemetrySnapshotPolicy.rebind(previous)
        val seeded = DjiTelemetrySnapshotPolicy.cachedVelocity(rebound, cachedVelocity)
        assertEquals(0L, seeded.velocityUpdatedAtNanos)

        val received = DjiTelemetrySnapshotPolicy.velocityPush(seeded, pushedVelocity, 300L)
        assertEquals(300L, received.velocityUpdatedAtNanos)
        assertEquals(pushedVelocity, received.velocity)
        assertSame(received, DjiTelemetrySnapshotPolicy.cachedVelocity(received, cachedVelocity))
    }
}
