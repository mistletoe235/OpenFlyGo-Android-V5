package edu.playground.djivln.survey

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sin
import kotlin.math.sqrt

data class SurveyExecutionTelemetry(
    val connected: Boolean,
    val simulatorActive: Boolean,
    val simulatorFlying: Boolean,
    val virtualStickEnabled: Boolean,
    val sticksActive: Boolean,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val updatedAtEpochMillis: Long,
    val aircraftFlying: Boolean = simulatorFlying,
    val batteryPercent: Int = 100,
    val rcBatteryPercent: Int = 100,
    val rcSignalPercent: Int = 100,
    val satelliteCount: Int = 99,
    val gpsSignalUsable: Boolean = true,
    val homeLocationValid: Boolean = true,
    val homeLatitude: Double = Double.NaN,
    val homeLongitude: Double = Double.NaN,
    val goHomeHeightMeters: Int = 120,
    val maxFlightHeightMeters: Int = 120,
    val maxFlightRadiusMeters: Int = 10_000,
    val maxFlightRadiusEnabled: Boolean = true,
    val horizontalSpeedMetersPerSecond: Double = 0.0,
    val verticalSpeedMetersPerSecond: Double = 0.0,
    val goingHome: Boolean = false,
    val landing: Boolean = false,
)

enum class SurveyExecutionEnvironment {
    DJI_SIMULATOR,
    REAL_AIRCRAFT_MANUAL_TAKEOFF,
}

enum class SurveyExecutionBlock {
    AIRCRAFT_DISCONNECTED,
    SIMULATOR_REQUIRED,
    SIMULATOR_NOT_FLYING,
    SIMULATOR_MUST_BE_OFF,
    REAL_AIRCRAFT_NOT_FLYING,
    REAL_AIRCRAFT_NOT_STABLY_HOVERING,
    REAL_REQUIRES_MANUAL_TAKEOFF,
    TELEMETRY_STALE,
    GPS_UNAVAILABLE,
    MANUAL_TAKEOVER,
    VIRTUAL_STICK_REQUIRED,
    UNSUPPORTED_COORDINATE_FRAME,
    MISSION_TOO_LONG,
    MISSION_ALTITUDE_UNSAFE,
    CAMERA_TRIGGER_UNSAFE,
    RC_SIGNAL_WEAK,
    GPS_SATELLITES_LOW,
    GPS_SIGNAL_WEAK,
    HOME_LOCATION_REQUIRED,
    GO_HOME_HEIGHT_UNSAFE,
    MAX_FLIGHT_HEIGHT_TOO_LOW,
    MAX_FLIGHT_RADIUS_REQUIRED,
    MAX_FLIGHT_RADIUS_TOO_SMALL,
    FLIGHT_CONTROLLER_FAILSAFE_ACTIVE,
    TERRAIN_REAL_FLIGHT_NOT_VERIFIED,
}

data class SurveyExecutionGateResult(
    val allowed: Boolean,
    val blocks: Set<SurveyExecutionBlock>,
    val startDistanceMeters: Double,
)

object SurveySimulatorGate {
    const val MAX_TELEMETRY_AGE_MS = 1_500L
    const val MAX_MISSION_PATH_METERS = 100_000.0
    const val MIN_MISSION_ALTITUDE_METERS = 5.0
    const val MAX_MISSION_ALTITUDE_METERS = 120.0
    const val REAL_MIN_RC_SIGNAL_PERCENT = 40
    const val REAL_MIN_SATELLITES = 12

    fun evaluate(
        mission: SurveyMission,
        telemetry: SurveyExecutionTelemetry,
        nowEpochMillis: Long,
        requireVirtualStick: Boolean,
        allowNotFlying: Boolean = false,
        allowGroundedPositionUnavailable: Boolean = false,
        environment: SurveyExecutionEnvironment = SurveyExecutionEnvironment.DJI_SIMULATOR,
        checkPreflightReadiness: Boolean = true,
    ): SurveyExecutionGateResult {
        val blocks = linkedSetOf<SurveyExecutionBlock>()
        if (!telemetry.connected) blocks += SurveyExecutionBlock.AIRCRAFT_DISCONNECTED
        if (environment == SurveyExecutionEnvironment.DJI_SIMULATOR) {
            if (!telemetry.simulatorActive) blocks += SurveyExecutionBlock.SIMULATOR_REQUIRED
            if (!telemetry.simulatorFlying && !allowNotFlying) {
                blocks += SurveyExecutionBlock.SIMULATOR_NOT_FLYING
            }
        } else {
            if (telemetry.simulatorActive) blocks += SurveyExecutionBlock.SIMULATOR_MUST_BE_OFF
            if (!telemetry.aircraftFlying) blocks += SurveyExecutionBlock.REAL_AIRCRAFT_NOT_FLYING
            if (!requireVirtualStick && telemetry.aircraftFlying &&
                (telemetry.altitudeMeters < 1.5 ||
                    kotlin.math.abs(telemetry.horizontalSpeedMetersPerSecond) > 1.0 ||
                    kotlin.math.abs(telemetry.verticalSpeedMetersPerSecond) > 0.5)
            ) blocks += SurveyExecutionBlock.REAL_AIRCRAFT_NOT_STABLY_HOVERING
            if (checkPreflightReadiness && mission.constraints.takeoffMode != SurveyTakeoffMode.MANUAL) {
                blocks += SurveyExecutionBlock.REAL_REQUIRES_MANUAL_TAKEOFF
            }
            if (checkPreflightReadiness && telemetry.rcSignalPercent < REAL_MIN_RC_SIGNAL_PERCENT) {
                blocks += SurveyExecutionBlock.RC_SIGNAL_WEAK
            }
            if (checkPreflightReadiness && telemetry.satelliteCount < REAL_MIN_SATELLITES) {
                blocks += SurveyExecutionBlock.GPS_SATELLITES_LOW
            }
            if (checkPreflightReadiness && !telemetry.gpsSignalUsable) {
                blocks += SurveyExecutionBlock.GPS_SIGNAL_WEAK
            }
            if (checkPreflightReadiness && !telemetry.homeLocationValid) {
                blocks += SurveyExecutionBlock.HOME_LOCATION_REQUIRED
            }
            if (!checkPreflightReadiness && (telemetry.goingHome || telemetry.landing)) {
                blocks += SurveyExecutionBlock.FLIGHT_CONTROLLER_FAILSAFE_ACTIVE
            }
        }
        val telemetryAge = nowEpochMillis - telemetry.updatedAtEpochMillis
        if (telemetryAge < 0L || telemetryAge > MAX_TELEMETRY_AGE_MS) {
            blocks += SurveyExecutionBlock.TELEMETRY_STALE
        }
        val positionUnavailable = !telemetry.latitude.isFinite() || !telemetry.longitude.isFinite()
        if (positionUnavailable && !allowGroundedPositionUnavailable) {
            blocks += SurveyExecutionBlock.GPS_UNAVAILABLE
        }
        if (telemetry.sticksActive) blocks += SurveyExecutionBlock.MANUAL_TAKEOVER
        if (requireVirtualStick && !telemetry.virtualStickEnabled) {
            blocks += SurveyExecutionBlock.VIRTUAL_STICK_REQUIRED
        }
        if (checkPreflightReadiness && mission.coordinateFrame != "WGS84") {
            blocks += SurveyExecutionBlock.UNSUPPORTED_COORDINATE_FRAME
        }
        if (checkPreflightReadiness && environment == SurveyExecutionEnvironment.DJI_SIMULATOR &&
            mission.estimatedPathMeters > MAX_MISSION_PATH_METERS
        ) {
            blocks += SurveyExecutionBlock.MISSION_TOO_LONG
        }
        if (checkPreflightReadiness && (mission.constraints.safeTakeoffAltitudeMeters !in
            MIN_MISSION_ALTITUDE_METERS..MAX_MISSION_ALTITUDE_METERS || mission.waypoints.any {
                it.point.altitudeMeters !in MIN_MISSION_ALTITUDE_METERS..MAX_MISSION_ALTITUDE_METERS
            })) {
            blocks += SurveyExecutionBlock.MISSION_ALTITUDE_UNSAFE
        }
        val nadirCapture = SurveyPlanner.captureFeasibility(
            mission.cameraProfile, mission.constraints, oblique = false,
        )
        val obliqueCapture = SurveyPlanner.captureFeasibility(
            mission.cameraProfile, mission.constraints, oblique = true,
        )
        if (checkPreflightReadiness && (!nadirCapture.feasible ||
            (mission.constraints.collectionMode == SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION &&
                !obliqueCapture.feasible)
        )) {
            blocks += SurveyExecutionBlock.CAMERA_TRIGGER_UNSAFE
        }
        val start = mission.waypoints.firstOrNull()?.point
        val startDistance = if (start == null || !telemetry.latitude.isFinite() || !telemetry.longitude.isFinite()) {
            Double.POSITIVE_INFINITY
        } else {
            distanceMeters(telemetry.latitude, telemetry.longitude, start.latitude, start.longitude)
        }
        // Start distance remains diagnostic only. Long ferry transits to the first
        // survey strip are allowed and are constrained by the normal flight limits.
        if (checkPreflightReadiness &&
            environment == SurveyExecutionEnvironment.REAL_AIRCRAFT_MANUAL_TAKEOFF
        ) {
            if (mission.terrainPlan?.realFlightVerified == false) {
                blocks += SurveyExecutionBlock.TERRAIN_REAL_FLIGHT_NOT_VERIFIED
            }
            val maximumMissionAltitude = mission.waypoints.maxOfOrNull { it.point.altitudeMeters }
                ?: mission.constraints.safeTakeoffAltitudeMeters
            if (telemetry.goHomeHeightMeters <= 0 ||
                telemetry.goHomeHeightMeters + 0.5 < maximumMissionAltitude
            ) blocks += SurveyExecutionBlock.GO_HOME_HEIGHT_UNSAFE
            if (telemetry.maxFlightHeightMeters <= 0 ||
                telemetry.maxFlightHeightMeters + 0.5 < maximumMissionAltitude
            ) blocks += SurveyExecutionBlock.MAX_FLIGHT_HEIGHT_TOO_LOW
            if (telemetry.maxFlightRadiusEnabled) {
                if (telemetry.maxFlightRadiusMeters <= 0) {
                    blocks += SurveyExecutionBlock.MAX_FLIGHT_RADIUS_REQUIRED
                } else {
                    val radiusOriginLatitude = telemetry.homeLatitude.takeIf { it.isFinite() }
                        ?: telemetry.latitude
                    val radiusOriginLongitude = telemetry.homeLongitude.takeIf { it.isFinite() }
                        ?: telemetry.longitude
                    if (mission.waypoints.any {
                        distanceMeters(radiusOriginLatitude, radiusOriginLongitude,
                            it.point.latitude, it.point.longitude) > telemetry.maxFlightRadiusMeters
                    }) blocks += SurveyExecutionBlock.MAX_FLIGHT_RADIUS_TOO_SMALL
                }
            }
        }
        return SurveyExecutionGateResult(blocks.isEmpty(), blocks, startDistance)
    }

    private fun distanceMeters(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val meanLatitude = (latA + latB) / 2.0
        val north = (latB - latA) * 111_132.0
        val east = (lonB - lonA) * 111_320.0 * cos(Math.toRadians(meanLatitude))
        return hypot(north, east)
    }
}

enum class SurveyExecutionState {
    IDLE,
    ARMING,
    RUNNING,
    PAUSED,
    COMPLETED,
    ABORTED,
}

enum class SurveyExecutionPhase {
    SAFE_CLIMB,
    TRANSIT_TO_START,
    RECOVERY_TO_PAUSE,
    SURVEY,
    RETURN_HOME,
    RETURN_TO_START,
}

data class SurveyExecutionLeg(
    val phase: SurveyExecutionPhase,
    val target: SurveyWaypoint,
    val missionWaypointIndex: Int?,
)

data class SurveyExecutionStatus(
    val state: SurveyExecutionState,
    val waypointIndex: Int,
    val reason: String?,
)

data class SurveyRemainingEstimate(
    val currentSectionSeconds: Double,
    val totalSeconds: Double,
)

/** State transitions only; aircraft side effects stay in the Android adapter. */
class SurveySimulatorExecutionStateMachine(
    private val mission: SurveyMission,
    currentPoint: GeoPoint? = null,
    returnPoint: GeoPoint? = currentPoint,
) {
    private val legs = buildExecutionLegs(mission, currentPoint, returnPoint)
    private var pausedRecoveryPoint: GeoPoint? = null
    private val resumeLegs = ArrayDeque<SurveyExecutionLeg>()
    var executionLegIndex: Int = 0
        private set

    var status = SurveyExecutionStatus(SurveyExecutionState.IDLE, 0, null)
        private set

    val currentPhase: SurveyExecutionPhase
        get() = resumeLegs.firstOrNull()?.phase ?: legs[executionLegIndex].phase

    /** The durable phase of the underlying execution leg, excluding a transient recovery hop. */
    val checkpointPhase: SurveyExecutionPhase
        get() = legs[executionLegIndex].phase

    val currentTarget: SurveyWaypoint
        get() = resumeLegs.firstOrNull()?.target ?: if (status.state == SurveyExecutionState.PAUSED) {
            pausedRecoveryPoint?.let { point ->
                legs[executionLegIndex].target.copy(
                    point = point,
                    headingDegrees = legs[executionLegIndex].target.headingDegrees,
                    captureAction = CaptureAction.NONE,
                    captureIntervalMeters = null,
                )
            } ?: legs[executionLegIndex].target
        } else legs[executionLegIndex].target

    val requiresHeadingAlignmentBeforeTranslation: Boolean
        get() = resumeLegs.isNotEmpty() || legs[executionLegIndex].missionWaypointIndex == null

    val executionLegCount: Int
        get() = legs.size

    /**
     * Remaining wall-clock estimate from the live aircraft position. Travel time uses the
     * configured flight/takeoff speed; direction changes include yaw and stabilization time.
     */
    fun remainingEstimate(
        currentPosition: GeoPoint?,
        currentHeadingDegrees: Double = Double.NaN,
        currentHorizontalSpeedMetersPerSecond: Double = Double.NaN,
        currentVerticalSpeedMetersPerSecond: Double = Double.NaN,
    ): SurveyRemainingEstimate {
        if (status.state == SurveyExecutionState.COMPLETED || executionLegIndex !in legs.indices) {
            return SurveyRemainingEstimate(0.0, 0.0)
        }
        val sectionEnd = currentSectionEndIndex()
        var total = 0.0
        var section = 0.0
        var from = currentPosition ?: legs.getOrNull(executionLegIndex - 1)?.target?.point
            ?: legs[executionLegIndex].target.point
        var previousTarget = legs.getOrNull(executionLegIndex - 1)?.target
        for (index in executionLegIndex..legs.lastIndex) {
            val leg = legs[index]
            val horizontalDistance = horizontalDistanceMeters(from, leg.target.point)
            val verticalDistance = abs(leg.target.point.altitudeMeters - from.altitudeMeters)
            val configuredHorizontalSpeed = speedFor(leg)
            val configuredVerticalSpeed = verticalSpeedFor(leg)
            val horizontalSpeed = if (index == executionLegIndex) {
                effectiveLiveSpeed(
                    currentHorizontalSpeedMetersPerSecond,
                    configuredHorizontalSpeed,
                )
            } else configuredHorizontalSpeed
            val verticalSpeed = if (index == executionLegIndex) {
                effectiveLiveSpeed(
                    abs(currentVerticalSpeedMetersPerSecond),
                    configuredVerticalSpeed,
                )
            } else configuredVerticalSpeed
            var seconds = maxOf(
                horizontalDistance / horizontalSpeed,
                verticalDistance / verticalSpeed,
            )
            val previousHeading = if (index == executionLegIndex && currentHeadingDegrees.isFinite()) {
                currentHeadingDegrees
            } else {
                previousTarget?.headingDegrees
            }
            if (previousHeading != null) {
                val yawDelta = shortestAngleDegrees(previousHeading, leg.target.headingDegrees)
                if (yawDelta > SurveyWaypointFollower.HEADING_TOLERANCE_DEGREES) {
                    seconds += TURN_STABILIZATION_SECONDS +
                        yawDelta / SurveyWaypointFollower.MAX_YAW_RATE_DEGREES_PER_SECOND
                }
            }
            if (previousTarget != null) {
                if (abs(previousTarget.gimbalPitchDegrees - leg.target.gimbalPitchDegrees) > 5.0) {
                    seconds += GIMBAL_STABILIZATION_SECONDS
                }
            }
            seconds += SurveyEtaPolicy.captureDelaySeconds(leg.target.captureAction)
            total += seconds
            if (index <= sectionEnd) section += seconds
            from = leg.target.point
            previousTarget = leg.target
        }
        return SurveyRemainingEstimate(section, total)
    }

    private fun currentSectionEndIndex(): Int {
        val current = legs[executionLegIndex]
        if (current.phase == SurveyExecutionPhase.SURVEY) {
            val passIndex = current.target.passIndex
            var end = executionLegIndex
            while (end + 1 < legs.size && legs[end + 1].phase == SurveyExecutionPhase.SURVEY &&
                legs[end + 1].target.passIndex == passIndex
            ) end++
            return end
        }
        var end = executionLegIndex
        while (end + 1 < legs.size && legs[end + 1].phase == current.phase) end++
        return end
    }

    private fun speedFor(leg: SurveyExecutionLeg): Double =
        if (leg.phase == SurveyExecutionPhase.SAFE_CLIMB) {
            mission.constraints.takeoffSpeedMetersPerSecond
        } else {
            mission.constraints.speedForCaptureView(leg.target.captureView)
        }.coerceAtLeast(0.2)

    private fun verticalSpeedFor(leg: SurveyExecutionLeg): Double =
        (if (executionLegIndex > 0 &&
            leg.target.point.altitudeMeters < legs[executionLegIndex - 1].target.point.altitudeMeters
        ) mission.constraints.descentSpeedMetersPerSecond
        else mission.constraints.takeoffSpeedMetersPerSecond).coerceAtLeast(0.2)

    private fun effectiveLiveSpeed(actual: Double, configured: Double): Double {
        if (!actual.isFinite() || actual < 0.1) return configured
        return actual.coerceIn(configured * 0.35, configured * 1.25).coerceAtLeast(0.2)
    }

    private fun shortestAngleDegrees(a: Double, b: Double): Double =
        abs((b - a + 540.0) % 360.0 - 180.0)

    private fun horizontalDistanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val earthRadius = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val haversine = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
        return 2.0 * earthRadius * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    fun requestArm(gate: SurveyExecutionGateResult): SurveyExecutionStatus {
        status = if (gate.allowed) {
            SurveyExecutionStatus(SurveyExecutionState.ARMING, 0, null)
        } else {
            SurveyExecutionStatus(
                SurveyExecutionState.ABORTED,
                0,
                "arm blocked: ${gate.blocks.joinToString(",")}",
            )
        }
        return status
    }

    fun onVirtualStickReady(gate: SurveyExecutionGateResult): SurveyExecutionStatus {
        if (status.state != SurveyExecutionState.ARMING) return status
        status = if (gate.allowed) {
            SurveyExecutionStatus(SurveyExecutionState.RUNNING, 0, null)
        } else {
            SurveyExecutionStatus(
                SurveyExecutionState.PAUSED,
                0,
                "run blocked: ${gate.blocks.joinToString(",")}",
            )
        }
        return status
    }

    fun validate(gate: SurveyExecutionGateResult): SurveyExecutionStatus {
        if (status.state != SurveyExecutionState.RUNNING) return status
        if (!gate.allowed) {
            status = SurveyExecutionStatus(
                SurveyExecutionState.PAUSED,
                status.waypointIndex,
                "runtime gate: ${gate.blocks.joinToString(",")}",
            )
        }
        return status
    }

    fun reachWaypoint(): SurveyExecutionStatus {
        if (status.state != SurveyExecutionState.RUNNING) return status
        if (resumeLegs.isNotEmpty()) {
            resumeLegs.removeFirst()
            if (resumeLegs.isEmpty()) pausedRecoveryPoint = null
            return status
        }
        val nextLeg = executionLegIndex + 1
        status = if (nextLeg >= legs.size) {
            SurveyExecutionStatus(SurveyExecutionState.COMPLETED, mission.waypoints.lastIndex, null)
        } else {
            executionLegIndex = nextLeg
            val waypointIndex = legs[nextLeg].missionWaypointIndex ?: status.waypointIndex
            SurveyExecutionStatus(SurveyExecutionState.RUNNING, waypointIndex, null)
        }
        return status
    }

    /** Marks completion only after DJI has accepted the automatic Return-to-Home action. */
    fun acceptDjiReturnHome(): SurveyExecutionStatus {
        if (status.state != SurveyExecutionState.RUNNING ||
            mission.constraints.completionAction != SurveyCompletionAction.RETURN_TO_HOME ||
            currentPhase != SurveyExecutionPhase.RETURN_HOME
        ) return status
        resumeLegs.clear()
        pausedRecoveryPoint = null
        executionLegIndex = legs.lastIndex
        status = SurveyExecutionStatus(
            state = SurveyExecutionState.COMPLETED,
            waypointIndex = mission.waypoints.lastIndex,
            reason = null,
        )
        return status
    }

    fun pause(): SurveyExecutionStatus {
        return pause(null)
    }

    fun pause(reason: String?): SurveyExecutionStatus {
        return pause(reason, null)
    }

    fun pause(reason: String?, recoveryPoint: GeoPoint?): SurveyExecutionStatus {
        if (status.state == SurveyExecutionState.RUNNING ||
            status.state == SurveyExecutionState.ARMING
        ) {
            status = status.copy(state = SurveyExecutionState.PAUSED, reason = reason)
            pausedRecoveryPoint = recoveryPoint
            resumeLegs.clear()
        }
        return status
    }

    fun resume(gate: SurveyExecutionGateResult): SurveyExecutionStatus {
        return resume(gate, null, Double.NaN)
    }

    fun resume(
        gate: SurveyExecutionGateResult,
        currentPoint: GeoPoint?,
        currentHeadingDegrees: Double = Double.NaN,
    ): SurveyExecutionStatus {
        if (status.state != SurveyExecutionState.PAUSED) return status
        status = if (gate.allowed) {
            val recoveryPoint = pausedRecoveryPoint
            if (currentPoint != null && recoveryPoint != null) {
                buildResumeLegs(currentPoint, currentHeadingDegrees, recoveryPoint)
            } else {
                pausedRecoveryPoint = null
            }
            status.copy(state = SurveyExecutionState.RUNNING, reason = null)
        } else status.copy(state = SurveyExecutionState.PAUSED, reason = "resume blocked: ${gate.blocks.joinToString(",")}")
        return status
    }

    fun abort(reason: String): SurveyExecutionStatus {
        status = status.copy(state = SurveyExecutionState.ABORTED, reason = reason)
        return status
    }

    fun restorePaused(waypointIndex: Int, legIndex: Int? = null): SurveyExecutionStatus {
        return restorePaused(waypointIndex, legIndex, null)
    }

    fun restorePaused(
        waypointIndex: Int,
        legIndex: Int? = null,
        recoveryPoint: GeoPoint?,
    ): SurveyExecutionStatus {
        require(waypointIndex in mission.waypoints.indices) { "checkpoint waypoint is out of range" }
        executionLegIndex = legIndex?.takeIf { it in legs.indices }
            ?: legs.indexOfFirst { it.missionWaypointIndex == waypointIndex }.takeIf { it >= 0 }
            ?: 0
        status = SurveyExecutionStatus(
            SurveyExecutionState.PAUSED,
            waypointIndex,
            "restored after process restart",
        )
        pausedRecoveryPoint = recoveryPoint
        resumeLegs.clear()
        return status
    }

    /**
     * Restores by identifiers that remain stable when launch-dependent SAFE_CLIMB/TRANSIT legs are
     * rebuilt. An absolute execution-leg index is deliberately not accepted here.
     */
    fun restorePausedStable(
        waypointIndex: Int,
        phase: SurveyExecutionPhase,
        phaseLegOrdinal: Int?,
        recoveryPoint: GeoPoint?,
    ): SurveyExecutionStatus {
        require(waypointIndex in mission.waypoints.indices) { "checkpoint waypoint is out of range" }
        executionLegIndex = stableLegIndex(waypointIndex, phase, phaseLegOrdinal)
        status = SurveyExecutionStatus(
            SurveyExecutionState.PAUSED,
            waypointIndex,
            "restored after process restart",
        )
        pausedRecoveryPoint = recoveryPoint
        resumeLegs.clear()
        return status
    }

    private fun buildResumeLegs(
        currentPoint: GeoPoint,
        currentHeadingDegrees: Double,
        recoveryPoint: GeoPoint,
    ) {
        resumeLegs.clear()
        val underlyingTarget = legs[executionLegIndex].target
        val horizontalDistance = horizontalDistanceMeters(currentPoint, recoveryPoint)
        val safeAltitude = if (horizontalDistance > SurveyWaypointFollower.HORIZONTAL_TOLERANCE_METERS) {
            maxOf(
                currentPoint.altitudeMeters,
                recoveryPoint.altitudeMeters,
                mission.constraints.safeTakeoffAltitudeMeters,
            )
        } else {
            maxOf(currentPoint.altitudeMeters, recoveryPoint.altitudeMeters)
        }
        val currentHeading = currentHeadingDegrees.takeIf(Double::isFinite)
            ?: underlyingTarget.headingDegrees
        fun recoveryLeg(point: GeoPoint, phase: SurveyExecutionPhase, heading: Double) =
            SurveyExecutionLeg(
                phase = phase,
                target = underlyingTarget.copy(
                    point = point,
                    headingDegrees = heading,
                    gimbalPitchDegrees = 0.0,
                    kind = SurveyWaypointKind.TRANSIT,
                    captureAction = CaptureAction.NONE,
                    captureIntervalMeters = null,
                ),
                missionWaypointIndex = null,
            )
        if (safeAltitude - currentPoint.altitudeMeters > SurveyWaypointFollower.VERTICAL_TOLERANCE_METERS) {
            resumeLegs += recoveryLeg(
                currentPoint.copy(altitudeMeters = safeAltitude),
                SurveyExecutionPhase.SAFE_CLIMB,
                currentHeading,
            )
        }
        if (horizontalDistance > SurveyWaypointFollower.HORIZONTAL_TOLERANCE_METERS) {
            resumeLegs += recoveryLeg(
                recoveryPoint.copy(altitudeMeters = safeAltitude),
                SurveyExecutionPhase.RECOVERY_TO_PAUSE,
                bearingDegrees(currentPoint, recoveryPoint),
            )
        }
        if (safeAltitude - recoveryPoint.altitudeMeters > SurveyWaypointFollower.VERTICAL_TOLERANCE_METERS) {
            resumeLegs += recoveryLeg(
                recoveryPoint,
                SurveyExecutionPhase.RECOVERY_TO_PAUSE,
                underlyingTarget.headingDegrees,
            )
        }
        if (resumeLegs.isEmpty()) pausedRecoveryPoint = null
    }

    /** Stable ordinal among legs with the same durable phase. */
    fun phaseLegOrdinal(legIndex: Int = executionLegIndex): Int {
        require(legIndex in legs.indices) { "execution leg is out of range" }
        val phase = legs[legIndex].phase
        return legs.take(legIndex + 1).count { it.phase == phase } - 1
    }

    fun executionLegIndexForMissionWaypoint(waypointIndex: Int): Int? =
        legs.indexOfFirst { it.missionWaypointIndex == waypointIndex }.takeIf { it >= 0 }

    fun phaseLegOrdinalForMissionWaypoint(waypointIndex: Int): Int? =
        executionLegIndexForMissionWaypoint(waypointIndex)?.let(::phaseLegOrdinal)

    private fun stableLegIndex(
        waypointIndex: Int,
        requestedPhase: SurveyExecutionPhase,
        phaseLegOrdinal: Int?,
    ): Int {
        // RECOVERY_TO_PAUSE is a transient target layered over an underlying durable leg. Legacy
        // checkpoints did not persist that underlying phase, so conservatively resume the mission
        // waypoint rather than trusting their absolute leg index.
        val phase = requestedPhase.takeUnless { it == SurveyExecutionPhase.RECOVERY_TO_PAUSE }
            ?: SurveyExecutionPhase.SURVEY
        val phaseCandidates = legs.withIndex().filter { it.value.phase == phase }
        val ordinalCandidate = phaseLegOrdinal
            ?.let { ordinal -> phaseCandidates.getOrNull(ordinal) }
        if (ordinalCandidate != null &&
            (ordinalCandidate.value.missionWaypointIndex == null ||
                ordinalCandidate.value.missionWaypointIndex == waypointIndex)
        ) return ordinalCandidate.index

        // Mission waypoints are the primary durable identity for survey legs. This also provides a
        // conservative migration for schema <= 4 checkpoints that have no phase ordinal.
        if (phase == SurveyExecutionPhase.SURVEY) {
            legs.indexOfFirst {
                it.phase == SurveyExecutionPhase.SURVEY && it.missionWaypointIndex == waypointIndex
            }.takeIf { it >= 0 }?.let { return it }
        }

        // Synthetic phases have no mission waypoint identity. Restart at the first leg in that
        // phase when an old/mismatched ordinal cannot be proven, favoring re-flight over skipping.
        phaseCandidates.firstOrNull()?.let { return it.index }
        legs.indexOfFirst {
            it.phase == SurveyExecutionPhase.SURVEY && it.missionWaypointIndex == waypointIndex
        }.takeIf { it >= 0 }?.let { return it }
        error("checkpoint phase $requestedPhase is unavailable in rebuilt execution plan")
    }

    fun pausedRecoveryPoint(): GeoPoint? = pausedRecoveryPoint

    fun reset(): SurveyExecutionStatus {
        executionLegIndex = 0
        status = SurveyExecutionStatus(SurveyExecutionState.IDLE, 0, null)
        return status
    }

    companion object {
        private const val TURN_STABILIZATION_SECONDS = 2.0
        private const val GIMBAL_STABILIZATION_SECONDS = 3.0

        fun buildExecutionLegs(
            mission: SurveyMission,
            currentPoint: GeoPoint?,
            returnPoint: GeoPoint? = currentPoint,
        ): List<SurveyExecutionLeg> {
            val result = mutableListOf<SurveyExecutionLeg>()
            val trackRoute = mission.constraints.collectionMode !=
                SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION ||
                mission.constraints.obliqueHeadingMode == SurveyObliqueHeadingMode.TRACK_ROUTE
            val routeHeadingsByPass = if (trackRoute) mission.waypoints
                .groupBy { it.passIndex }
                .mapValues { (_, waypoints) ->
                    val start = waypoints.firstOrNull { it.kind == SurveyWaypointKind.PASS_START }
                    val end = waypoints.firstOrNull { it.kind == SurveyWaypointKind.PASS_END }
                    if (start == null || end == null) null else bearingDegrees(start.point, end.point)
                } else emptyMap()
            val surveyWaypoints = mission.waypoints.map { waypoint ->
                routeHeadingsByPass[waypoint.passIndex]?.let { heading ->
                    waypoint.copy(headingDegrees = heading)
                } ?: waypoint
            }
            val first = surveyWaypoints.first()
            val last = surveyWaypoints.last()
            val safeCruiseAltitude = maxOf(
                mission.constraints.safeTakeoffAltitudeMeters,
                first.point.altitudeMeters,
            )
            fun transit(
                point: GeoPoint,
                phase: SurveyExecutionPhase,
                headingDegrees: Double = first.headingDegrees,
                gimbalPitchDegrees: Double = -90.0,
            ) = SurveyExecutionLeg(
                phase = phase,
                target = SurveyWaypoint(
                    point = point,
                    headingDegrees = headingDegrees,
                    gimbalPitchDegrees = gimbalPitchDegrees,
                    kind = SurveyWaypointKind.TRANSIT,
                    captureAction = CaptureAction.NONE,
                    passIndex = 0,
                ),
                missionWaypointIndex = null,
            )
            if (currentPoint != null) {
                result += transit(
                    currentPoint.copy(altitudeMeters = safeCruiseAltitude),
                    SurveyExecutionPhase.SAFE_CLIMB,
                )
                result += transit(
                    first.point.copy(altitudeMeters = safeCruiseAltitude),
                    SurveyExecutionPhase.TRANSIT_TO_START,
                    headingDegrees = bearingDegrees(currentPoint, first.point),
                    gimbalPitchDegrees = 0.0,
                )
            }
            var previousSurveyPoint: GeoPoint? = null
            surveyWaypoints.forEachIndexed { index, waypoint ->
                if (trackRoute && waypoint.kind == SurveyWaypointKind.PASS_START &&
                    previousSurveyPoint != null
                ) {
                    result += transit(
                        waypoint.point,
                        SurveyExecutionPhase.SURVEY,
                        headingDegrees = bearingDegrees(previousSurveyPoint!!, waypoint.point),
                        gimbalPitchDegrees = waypoint.gimbalPitchDegrees,
                    )
                }
                result += SurveyExecutionLeg(SurveyExecutionPhase.SURVEY, waypoint, index)
                previousSurveyPoint = waypoint.point
            }
            if (mission.constraints.completionAction == SurveyCompletionAction.RETURN_TO_HOME &&
                returnPoint != null
            ) {
                val returnHeading = bearingDegrees(last.point, returnPoint)
                result += transit(
                    last.point.copy(altitudeMeters = safeCruiseAltitude),
                    SurveyExecutionPhase.RETURN_HOME,
                    headingDegrees = last.headingDegrees,
                    gimbalPitchDegrees = 0.0,
                )
                result += transit(
                    returnPoint.copy(altitudeMeters = safeCruiseAltitude),
                    SurveyExecutionPhase.RETURN_HOME,
                    headingDegrees = returnHeading,
                    gimbalPitchDegrees = 0.0,
                )
                // Only look down after arriving above Home; the final leg is the descent.
                result += transit(
                    returnPoint,
                    SurveyExecutionPhase.RETURN_HOME,
                    headingDegrees = returnHeading,
                    gimbalPitchDegrees = -90.0,
                )
            } else if (mission.constraints.completionAction == SurveyCompletionAction.RETURN_TO_ROUTE_START) {
                val returnHeading = bearingDegrees(last.point, first.point)
                result += transit(
                    last.point.copy(altitudeMeters = safeCruiseAltitude),
                    SurveyExecutionPhase.RETURN_TO_START,
                    headingDegrees = last.headingDegrees,
                    gimbalPitchDegrees = 0.0,
                )
                result += transit(
                    first.point.copy(altitudeMeters = safeCruiseAltitude),
                    SurveyExecutionPhase.RETURN_TO_START,
                    headingDegrees = returnHeading,
                    gimbalPitchDegrees = 0.0,
                )
                result += transit(
                    first.point,
                    SurveyExecutionPhase.RETURN_TO_START,
                    headingDegrees = returnHeading,
                    gimbalPitchDegrees = -90.0,
                )
            }
            return result
        }

        private fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
            val fromLatitude = Math.toRadians(from.latitude)
            val toLatitude = Math.toRadians(to.latitude)
            val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
            val y = sin(longitudeDelta) * cos(toLatitude)
            val x = cos(fromLatitude) * sin(toLatitude) -
                sin(fromLatitude) * cos(toLatitude) * cos(longitudeDelta)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }
    }
}
