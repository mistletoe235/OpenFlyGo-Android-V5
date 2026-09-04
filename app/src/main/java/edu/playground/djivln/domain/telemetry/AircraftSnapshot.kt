package edu.playground.djivln.domain.telemetry

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null
)

data class AttitudeDegrees(
    val roll: Double,
    val pitch: Double,
    val yaw: Double
)

data class VelocityMetersPerSecond(
    val north: Double,
    val east: Double,
    val up: Double
)

data class AircraftSnapshot(
    val connected: Boolean = false,
    val productId: Int? = null,
    val aircraftLocation: GeoPoint? = null,
    val aircraftLocationUpdatedAtNanos: Long = 0L,
    val homeLocation: GeoPoint? = null,
    val homeLocationUpdatedAtNanos: Long = 0L,
    val remoteControllerLocation: GeoPoint? = null,
    val remoteControllerLocationUpdatedAtNanos: Long = 0L,
    val remoteControllerGpsValid: Boolean? = null,
    val relativeAltitudeMeters: Double? = null,
    val relativeAltitudeUpdatedAtNanos: Long = 0L,
    val takeoffAbsoluteAltitudeMeters: Double? = null,
    val takeoffAbsoluteAltitudeUpdatedAtNanos: Long = 0L,
    val altitudeAboveSeaLevelMeters: Double? = null,
    val altitudeAboveGroundMeters: Double? = null,
    val attitude: AttitudeDegrees? = null,
    val attitudeUpdatedAtNanos: Long = 0L,
    val velocity: VelocityMetersPerSecond? = null,
    val velocityUpdatedAtNanos: Long = 0L,
    val headingDegrees: Double? = null,
    val headingUpdatedAtNanos: Long = 0L,
    val aircraftBatteryPercent: Int? = null,
    val aircraftLeftBatteryPercent: Int? = null,
    val aircraftRightBatteryPercent: Int? = null,
    val remoteControllerBatteryPercent: Int? = null,
    val remoteControllerInternalBatteryPercent: Int? = null,
    val remoteControllerExternalBatteryPercent: Int? = null,
    val remoteControllerSignalPercent: Int? = null,
    val leftStickHorizontal: Int = 0,
    val leftStickVertical: Int = 0,
    val rightStickHorizontal: Int = 0,
    val rightStickVertical: Int = 0,
    val sticksActive: Boolean = false,
    val gpsSatelliteCount: Int? = null,
    val gpsSignalLevel: String? = null,
    val goHomeHeightMeters: Int? = null,
    val goHomeState: String? = null,
    val goHomeConfirmType: String? = null,
    val autoRthReason: String? = null,
    val failsafeActive: Boolean? = null,
    val failsafeAction: String? = null,
    val lowBatteryRthEnabled: Boolean? = null,
    val lowBatteryRthState: String? = null,
    val smartRthCountdownSeconds: Int? = null,
    val remainingFlightTimeSeconds: Int? = null,
    val timeNeededToGoHomeSeconds: Int? = null,
    val timeNeededToLandSeconds: Int? = null,
    val batteryNeededToGoHomePercent: Int? = null,
    val batteryNeededToLandPercent: Int? = null,
    val maxSafeFlightRadiusMeters: Double? = null,
    val maxFlightHeightMeters: Int? = null,
    val maxFlightRadiusMeters: Int? = null,
    val maxFlightRadiusEnabled: Boolean? = null,
    val gimbalPitchDegrees: Double? = null,
    val flightMode: String? = null,
    val isFlying: Boolean = false,
    val landingConfirmationNeeded: Boolean = false,
    val motorsOn: Boolean = false,
    val simulatorActive: Boolean = false,
    /** Receipt time of flight/pose state only; battery/link/UI updates must not refresh it. */
    val flightStateUpdatedAtNanos: Long = 0L,
    val updatedAtNanos: Long = 0L,
    val unsupportedFields: Set<String> = emptySet()
)

fun interface AircraftSnapshotListener {
    fun onSnapshot(snapshot: AircraftSnapshot)
}

interface AircraftTelemetrySource {
    fun start(listener: AircraftSnapshotListener)
    fun stop()
    fun snapshot(): AircraftSnapshot
}
