package edu.playground.djivln.adapter.dji

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.telemetry.VelocityMetersPerSecond

internal object DjiTelemetrySnapshotPolicy {
    fun rebind(snapshot: AircraftSnapshot): AircraftSnapshot = snapshot.copy(
        unsupportedFields = emptySet(),
        velocity = null,
        velocityUpdatedAtNanos = 0L,
        gimbalPitchDegrees = null,
        gimbalRollDegrees = null,
        gimbalYawDegrees = null,
        gimbalYawRelativeToAircraftHeadingDegrees = null,
        gimbalAttitudeUpdatedAtNanos = 0L,
        gimbalYawRelativeUpdatedAtNanos = 0L,
    )

    fun velocityPush(
        snapshot: AircraftSnapshot,
        velocity: VelocityMetersPerSecond?,
        receivedAtNanos: Long,
    ): AircraftSnapshot = snapshot.copy(
        velocity = velocity,
        velocityUpdatedAtNanos = receivedAtNanos,
        flightStateUpdatedAtNanos = receivedAtNanos,
    )

    fun cachedVelocity(snapshot: AircraftSnapshot, velocity: VelocityMetersPerSecond?): AircraftSnapshot =
        if (snapshot.velocityUpdatedAtNanos > 0L) snapshot else snapshot.copy(velocity = velocity)
}
