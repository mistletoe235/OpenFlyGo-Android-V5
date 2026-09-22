package edu.playground.djivln.survey

data class SurveyFrameTelemetry(
    val sampledAtNanos: Long,
    val aircraftLocation: GeoPoint?,
    val aircraftLocationUpdatedAtNanos: Long,
    val altitudeAboveSeaLevelMeters: Double?,
    val relativeAltitudeMeters: Double?,
    val altitudeAboveGroundMeters: Double?,
    val headingDegrees: Double?,
    val headingUpdatedAtNanos: Long,
    val rollDegrees: Double?,
    val pitchDegrees: Double?,
    val yawDegrees: Double?,
    val attitudeUpdatedAtNanos: Long,
    val velocityNorthMetersPerSecond: Double?,
    val velocityEastMetersPerSecond: Double?,
    val velocityUpMetersPerSecond: Double?,
    val velocityUpdatedAtNanos: Long,
    val gimbalPitchDegrees: Double?,
    val gpsSatelliteCount: Int?,
    val gpsSignalLevel: String?,
    val gimbalRollDegrees: Double? = null,
    val gimbalYawDegrees: Double? = null,
    val gimbalYawRelativeToAircraftHeadingDegrees: Double? = null,
    val gimbalAttitudeUpdatedAtNanos: Long = 0L,
    val gimbalYawRelativeUpdatedAtNanos: Long = 0L,
)

data class SurveyFrameMetadata(
    val frameEpochMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeAboveSeaLevelMeters: Double?,
    val relativeAltitudeMeters: Double?,
    val altitudeAboveGroundMeters: Double?,
    val headingDegrees: Double?,
    val rollDegrees: Double?,
    val pitchDegrees: Double?,
    val yawDegrees: Double?,
    val velocityNorthMetersPerSecond: Double?,
    val velocityEastMetersPerSecond: Double?,
    val velocityUpMetersPerSecond: Double?,
    val groundSpeedMetersPerSecond: Double?,
    val groundTrackDegrees: Double?,
    val gimbalPitchDegrees: Double?,
    val gpsSatelliteCount: Int?,
    val gpsSignalLevel: String?,
    val gpsAgeMillis: Long?,
    val headingAgeMillis: Long?,
    val attitudeAgeMillis: Long?,
    val velocityAgeMillis: Long?,
    val frameAfterTriggerMillis: Long,
    val telemetryAfterFrameMillis: Long,
    val altitudeAboveSeaLevelSource: String? = null,
    val gimbalRollDegrees: Double? = null,
    val gimbalYawDegrees: Double? = null,
    val gimbalYawRelativeToAircraftHeadingDegrees: Double? = null,
    val cameraRollDegrees: Double? = null,
    val cameraPitchDegrees: Double? = null,
    val cameraYawDegrees: Double? = null,
    val cameraYawSource: String? = null,
    val cameraYawConsistencyErrorDegrees: Double? = null,
    val gimbalAttitudeAgeMillis: Long? = null,
    val gimbalYawRelativeAgeMillis: Long? = null,
) {
    val hasFreshAircraftGps: Boolean
        get() = latitude != null && longitude != null
}

object SurveyFrameMetadataPolicy {
    const val MAX_AIRCRAFT_GPS_AGE_MILLIS = 2_000L

    fun resolve(
        trigger: SurveyPhotoTrigger,
        frameCapturedAtNanos: Long,
        telemetry: SurveyFrameTelemetry?,
    ): SurveyFrameMetadata {
        val frameAfterTriggerMillis = nanosToMillis(frameCapturedAtNanos - trigger.triggeredAtNanos)
        val frameEpochMillis = trigger.triggeredAtEpochMillis + frameAfterTriggerMillis
        val gpsAgeMillis = telemetry
            ?.takeIf { it.aircraftLocationUpdatedAtNanos > 0L }
            ?.let { nanosToMillis(it.sampledAtNanos - it.aircraftLocationUpdatedAtNanos) }
        val location = telemetry?.aircraftLocation?.takeIf { point ->
            gpsAgeMillis in 0..MAX_AIRCRAFT_GPS_AGE_MILLIS &&
                point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
                point.longitude.isFinite() && point.longitude in -180.0..180.0 &&
                !(point.latitude == 0.0 && point.longitude == 0.0)
        }
        val headingAgeMillis = telemetry
            ?.takeIf { it.headingUpdatedAtNanos > 0L }
            ?.let { nanosToMillis(it.sampledAtNanos - it.headingUpdatedAtNanos) }
        val attitudeAgeMillis = telemetry
            ?.takeIf { it.attitudeUpdatedAtNanos > 0L }
            ?.let { nanosToMillis(it.sampledAtNanos - it.attitudeUpdatedAtNanos) }
        val velocityAgeMillis = telemetry
            ?.takeIf { it.velocityUpdatedAtNanos > 0L }
            ?.let { nanosToMillis(it.sampledAtNanos - it.velocityUpdatedAtNanos) }
        val gimbalAttitudeAgeMillis = telemetry
            ?.takeIf { it.gimbalAttitudeUpdatedAtNanos > 0L }
            ?.let { nanosToMillis(it.sampledAtNanos - it.gimbalAttitudeUpdatedAtNanos) }
        val gimbalYawRelativeAgeMillis = telemetry
            ?.takeIf { it.gimbalYawRelativeUpdatedAtNanos > 0L }
            ?.let { nanosToMillis(it.sampledAtNanos - it.gimbalYawRelativeUpdatedAtNanos) }
        // DJI state listeners may only publish when a value changes. The snapshot is sampled at
        // frame receipt, so an unchanged attitude/velocity remains the current value even when its
        // change timestamp is old. Gate these fields on fresh aircraft GPS, while retaining the
        // original ages in metadata for audit.
        val poseUsable = location != null
        val north = telemetry?.velocityNorthMetersPerSecond?.takeIf { poseUsable && it.isFinite() }
        val east = telemetry?.velocityEastMetersPerSecond?.takeIf { poseUsable && it.isFinite() }
        val up = telemetry?.velocityUpMetersPerSecond?.takeIf { poseUsable && it.isFinite() }
        val groundSpeed = if (north != null && east != null) kotlin.math.hypot(north, east) else null
        val groundTrack = if (groundSpeed != null && groundSpeed > MIN_TRACK_SPEED_METERS_PER_SECOND) {
            normalizeHeading(Math.toDegrees(kotlin.math.atan2(requireNotNull(east), requireNotNull(north))))
        } else {
            null
        }
        val absoluteAltitude = telemetry?.altitudeAboveSeaLevelMeters
            ?.takeIf { location != null && isCredibleAbsoluteAltitude(it, telemetry.relativeAltitudeMeters) }
        val cameraOrientation = CameraOrientationResolver.resolve(
            // Camera attitude remains useful when aircraft GPS is unavailable (for example during
            // an indoor ground check). Only standard geotags are gated by fresh GPS below.
            aircraftHeadingDegrees = telemetry?.headingDegrees,
            gimbalRollDegrees = telemetry?.gimbalRollDegrees,
            gimbalPitchDegrees = telemetry?.gimbalPitchDegrees,
            absoluteGimbalYawDegrees = telemetry?.gimbalYawDegrees,
            relativeGimbalYawDegrees = telemetry?.gimbalYawRelativeToAircraftHeadingDegrees,
        )
        return SurveyFrameMetadata(
            frameEpochMillis = frameEpochMillis,
            latitude = location?.latitude,
            longitude = location?.longitude,
            altitudeAboveSeaLevelMeters = absoluteAltitude,
            relativeAltitudeMeters = telemetry?.relativeAltitudeMeters?.takeIf(Double::isFinite),
            altitudeAboveGroundMeters = telemetry?.altitudeAboveGroundMeters?.takeIf(Double::isFinite),
            headingDegrees = telemetry?.headingDegrees
                ?.takeIf { poseUsable && it.isFinite() }
                ?.let(::normalizeHeading),
            rollDegrees = telemetry?.rollDegrees?.takeIf { poseUsable && it.isFinite() },
            pitchDegrees = telemetry?.pitchDegrees?.takeIf { poseUsable && it.isFinite() },
            yawDegrees = telemetry?.yawDegrees?.takeIf { poseUsable && it.isFinite() }?.let(::normalizeHeading),
            velocityNorthMetersPerSecond = north,
            velocityEastMetersPerSecond = east,
            velocityUpMetersPerSecond = up,
            groundSpeedMetersPerSecond = groundSpeed,
            groundTrackDegrees = groundTrack,
            gimbalPitchDegrees = telemetry?.gimbalPitchDegrees?.takeIf(Double::isFinite),
            gpsSatelliteCount = telemetry?.gpsSatelliteCount?.takeIf { it >= 0 },
            gpsSignalLevel = telemetry?.gpsSignalLevel,
            gpsAgeMillis = gpsAgeMillis,
            headingAgeMillis = headingAgeMillis,
            attitudeAgeMillis = attitudeAgeMillis,
            velocityAgeMillis = velocityAgeMillis,
            frameAfterTriggerMillis = frameAfterTriggerMillis,
            telemetryAfterFrameMillis = telemetry?.let {
                nanosToMillis(it.sampledAtNanos - frameCapturedAtNanos)
            } ?: 0L,
            altitudeAboveSeaLevelSource = absoluteAltitude?.let { "aircraft_asl" },
            gimbalRollDegrees = telemetry?.gimbalRollDegrees?.takeIf(Double::isFinite),
            gimbalYawDegrees = telemetry?.gimbalYawDegrees?.takeIf(Double::isFinite)?.let(::normalizeHeading),
            gimbalYawRelativeToAircraftHeadingDegrees = telemetry
                ?.gimbalYawRelativeToAircraftHeadingDegrees
                ?.takeIf(Double::isFinite),
            cameraRollDegrees = cameraOrientation.rollDegrees,
            cameraPitchDegrees = cameraOrientation.pitchDegrees,
            cameraYawDegrees = cameraOrientation.yawDegrees,
            cameraYawSource = cameraOrientation.yawSource,
            cameraYawConsistencyErrorDegrees = cameraOrientation.yawConsistencyErrorDegrees,
            gimbalAttitudeAgeMillis = gimbalAttitudeAgeMillis,
            gimbalYawRelativeAgeMillis = gimbalYawRelativeAgeMillis,
        )
    }

    private fun nanosToMillis(value: Long): Long = value / 1_000_000L

    private fun normalizeHeading(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    private fun isCredibleAbsoluteAltitude(value: Double, relativeAltitude: Double?): Boolean {
        if (!value.isFinite()) return false
        // V5 Simulator commonly reports ASL=0 while simultaneously reporting a substantial
        // relative altitude. Treat that combination as unsupported instead of writing a false
        // zero into EXIF or sending it to reconstruction as a real ellipsoidal/sea-level height.
        return !(kotlin.math.abs(value) < 0.01 &&
            relativeAltitude?.isFinite() == true && kotlin.math.abs(relativeAltitude) > 2.0)
    }

    private const val MIN_TRACK_SPEED_METERS_PER_SECOND = 0.05
}
