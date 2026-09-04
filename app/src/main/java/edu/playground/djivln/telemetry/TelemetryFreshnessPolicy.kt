package edu.playground.djivln.telemetry

data class FlightTelemetryFreshnessInput(
    val nowMs: Long,
    val connected: Boolean,
    val altitudePushAtMs: Long?,
    val velocityPushAtMs: Long?,
    val positionPushAtMs: Long?,
    val headingPushAtMs: Long?,
    val altitudeMeters: Double?,
    val horizontalSpeedMetersPerSecond: Double?,
    val headingDegrees: Double?,
    val latitude: Double?,
    val longitude: Double?
)

class TelemetryFreshnessPolicy(
    private val maxAgeMs: Long = 1_500L
) {
    fun isFresh(input: FlightTelemetryFreshnessInput): Boolean {
        if (!input.connected) return false
        val timestamps = listOf(
            input.altitudePushAtMs,
            input.velocityPushAtMs,
            input.positionPushAtMs,
            input.headingPushAtMs
        )
        if (!timestamps.all { it != null && input.nowMs - it in 0..maxAgeMs }) return false
        if (input.altitudeMeters?.isFinite() != true) return false
        if (input.horizontalSpeedMetersPerSecond?.isFinite() != true) return false
        if (input.headingDegrees?.isFinite() != true) return false
        val latitude = input.latitude ?: return false
        val longitude = input.longitude ?: return false
        return latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(kotlin.math.abs(latitude) < 0.000001 && kotlin.math.abs(longitude) < 0.000001)
    }
}
