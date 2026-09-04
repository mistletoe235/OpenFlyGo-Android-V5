package edu.playground.djivln.survey

data class SurveyRealFlightEvidence(
    val simulatorRegressionPassed: Boolean,
    val failsafeRegressionPassed: Boolean,
    val fruBenchDirectionVerified: Boolean,
    val cameraCalibrated: Boolean,
    val operatingAreaReviewed: Boolean,
)

data class SurveyRealFlightTelemetry(
    val connected: Boolean,
    val flightStateFresh: Boolean,
    val flying: Boolean,
    val simulatorActive: Boolean,
    val batteryPercent: Int,
    val rcBatteryPercent: Int,
    val rcSignalPercent: Int,
    val satelliteCount: Int,
    val gpsSignalUsable: Boolean,
    val homeLocationValid: Boolean,
    val latitude: Double,
    val longitude: Double,
    val goHomeHeightMeters: Int,
    val maxFlightHeightMeters: Int,
    val maxFlightRadiusMeters: Int,
    val maxFlightRadiusEnabled: Boolean,
)

enum class SurveyRealFlightBlock {
    MISSION_REQUIRED,
    AIRCRAFT_DISCONNECTED,
    TELEMETRY_STALE,
    AIRCRAFT_MUST_BE_ON_GROUND,
    SIMULATOR_MUST_BE_OFF,
    BATTERY_BELOW_30_PERCENT,
    RC_BATTERY_BELOW_30_PERCENT,
    RC_SIGNAL_WEAK,
    GPS_BELOW_12_SATELLITES,
    GPS_SIGNAL_WEAK,
    HOME_LOCATION_REQUIRED,
    GO_HOME_HEIGHT_NOT_CONFIGURED,
    GO_HOME_HEIGHT_BELOW_MISSION,
    MAX_FLIGHT_HEIGHT_TOO_LOW,
    MAX_FLIGHT_RADIUS_REQUIRED,
    MAX_FLIGHT_RADIUS_TOO_SMALL,
    SIMULATOR_REGRESSION_REQUIRED,
    FAILSAFE_REGRESSION_REQUIRED,
    FRU_BENCH_VERIFICATION_REQUIRED,
    CAMERA_CALIBRATION_REQUIRED,
    OPERATING_AREA_REVIEW_REQUIRED,
}

data class SurveyRealFlightReadinessReport(
    val readyForReview: Boolean,
    val blocks: Set<SurveyRealFlightBlock>,
)

/** Readiness audit only. It never authorizes or unlocks real-aircraft execution. */
object SurveyRealFlightReadiness {
    const val MIN_AIRCRAFT_BATTERY_PERCENT = 30
    const val MIN_RC_BATTERY_PERCENT = 30
    const val MIN_RC_SIGNAL_PERCENT = 40
    const val MIN_SATELLITE_COUNT = 12

    fun evaluate(
        mission: SurveyMission?,
        telemetry: SurveyRealFlightTelemetry,
        evidence: SurveyRealFlightEvidence,
    ): SurveyRealFlightReadinessReport {
        val blocks = linkedSetOf<SurveyRealFlightBlock>()
        if (mission == null) blocks += SurveyRealFlightBlock.MISSION_REQUIRED
        if (!telemetry.connected) blocks += SurveyRealFlightBlock.AIRCRAFT_DISCONNECTED
        if (!telemetry.flightStateFresh) blocks += SurveyRealFlightBlock.TELEMETRY_STALE
        if (telemetry.flying) blocks += SurveyRealFlightBlock.AIRCRAFT_MUST_BE_ON_GROUND
        if (telemetry.simulatorActive) blocks += SurveyRealFlightBlock.SIMULATOR_MUST_BE_OFF
        if (telemetry.batteryPercent < MIN_AIRCRAFT_BATTERY_PERCENT) {
            blocks += SurveyRealFlightBlock.BATTERY_BELOW_30_PERCENT
        }
        if (telemetry.rcBatteryPercent < MIN_RC_BATTERY_PERCENT) {
            blocks += SurveyRealFlightBlock.RC_BATTERY_BELOW_30_PERCENT
        }
        if (telemetry.rcSignalPercent < MIN_RC_SIGNAL_PERCENT) {
            blocks += SurveyRealFlightBlock.RC_SIGNAL_WEAK
        }
        if (telemetry.satelliteCount < MIN_SATELLITE_COUNT) {
            blocks += SurveyRealFlightBlock.GPS_BELOW_12_SATELLITES
        }
        if (!telemetry.gpsSignalUsable) blocks += SurveyRealFlightBlock.GPS_SIGNAL_WEAK
        if (!telemetry.homeLocationValid) blocks += SurveyRealFlightBlock.HOME_LOCATION_REQUIRED
        mission?.let { value ->
            val maximumMissionAltitude = value.waypoints.maxOfOrNull { it.point.altitudeMeters }
                ?: value.constraints.safeTakeoffAltitudeMeters
            if (telemetry.goHomeHeightMeters <= 0) {
                blocks += SurveyRealFlightBlock.GO_HOME_HEIGHT_NOT_CONFIGURED
            } else if (telemetry.goHomeHeightMeters + 0.5 < maximumMissionAltitude) {
                blocks += SurveyRealFlightBlock.GO_HOME_HEIGHT_BELOW_MISSION
            }
            if (telemetry.maxFlightHeightMeters <= 0 ||
                telemetry.maxFlightHeightMeters + 0.5 < maximumMissionAltitude
            ) blocks += SurveyRealFlightBlock.MAX_FLIGHT_HEIGHT_TOO_LOW
            if (telemetry.maxFlightRadiusEnabled) {
                if (telemetry.maxFlightRadiusMeters <= 0) {
                    blocks += SurveyRealFlightBlock.MAX_FLIGHT_RADIUS_REQUIRED
                } else {
                    val maximumDistance = value.waypoints.maxOfOrNull {
                        distanceMeters(telemetry.latitude, telemetry.longitude,
                            it.point.latitude, it.point.longitude)
                    } ?: Double.POSITIVE_INFINITY
                    if (!maximumDistance.isFinite() || maximumDistance > telemetry.maxFlightRadiusMeters) {
                        blocks += SurveyRealFlightBlock.MAX_FLIGHT_RADIUS_TOO_SMALL
                    }
                }
            }
        }
        if (!evidence.simulatorRegressionPassed) blocks += SurveyRealFlightBlock.SIMULATOR_REGRESSION_REQUIRED
        if (!evidence.failsafeRegressionPassed) blocks += SurveyRealFlightBlock.FAILSAFE_REGRESSION_REQUIRED
        if (!evidence.fruBenchDirectionVerified) blocks += SurveyRealFlightBlock.FRU_BENCH_VERIFICATION_REQUIRED
        if (!evidence.cameraCalibrated) blocks += SurveyRealFlightBlock.CAMERA_CALIBRATION_REQUIRED
        if (!evidence.operatingAreaReviewed) {
            blocks += SurveyRealFlightBlock.OPERATING_AREA_REVIEW_REQUIRED
        }
        return SurveyRealFlightReadinessReport(blocks.isEmpty(), blocks)
    }

    private fun distanceMeters(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val north = (latB - latA) * 111_132.0
        val east = (lonB - lonA) * 111_320.0 *
            kotlin.math.cos(Math.toRadians((latA + latB) / 2.0))
        return kotlin.math.hypot(north, east)
    }
}
