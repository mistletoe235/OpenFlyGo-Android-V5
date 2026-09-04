package edu.playground.djivln.control

import android.content.Context
import edu.playground.djivln.R
import edu.playground.djivln.control.ControlVelocityCommand
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class PositionControlPose(
    val latitude: Double,
    val longitude: Double,
    val relativeAltitudeMeters: Double,
    val headingDegrees: Double,
    val timestampMs: Long,
    val rtkFixed: Boolean,
    val source: String,
    val simulated: Boolean = false,
    val positionTimestampMs: Long = timestampMs,
    val horizontalSpeedMetersPerSecond: Double? = null,
    val velocityTimestampMs: Long? = null,
    val verticalSpeedUpMetersPerSecond: Double? = null,
    val verticalVelocityTimestampMs: Long? = null,
    val downwardHeightMeters: Double? = null,
)

enum class PositionClosureMode {
    GPS_RTK,
    VELOCITY_ESTIMATE;

    fun label(context: Context?): String = when (this) {
        GPS_RTK -> "GPS/RTK"
        VELOCITY_ESTIMATE -> context?.getString(R.string.velocity_integration_experiment)
            ?: "velocity-integration experiment"
    }
}

/** Converts a model-relative body target into a fixed geographic target and closes position. */
class GpsPositionClosedLoop(
    private val maxPoseAgeMs: Long = 750L,
    private val maxActionTimeoutMs: Long = 6_000L,
    private val rtkPositionToleranceMeters: Double = 0.15,
    private val gpsPositionToleranceMeters: Double = 0.80,
    private val altitudeToleranceMeters: Double = 0.20,
    private val yawToleranceDegrees: Double = 4.0,
    private val rtkKp: Double = 1.0,
    private val gpsKp: Double = 0.6,
    rtkMaxHorizontalSpeed: Double = 2.0,
    private val maxVerticalSpeed: Double = 0.2,
    private val maxYawRateDegrees: Double = 10.0,
    context: Context? = null,
) {
    private val appContext = context?.applicationContext
    private var requestedMaxHorizontalSpeed = HorizontalSpeedSetting.normalize(rtkMaxHorizontalSpeed)

    data class Step(
        val command: ControlVelocityCommand,
        val terminal: Boolean,
        val successful: Boolean,
        val reason: String,
        val horizontalErrorMeters: Double,
        val verticalErrorMeters: Double = 0.0,
    )

    private data class Target(
        val mode: PositionClosureMode,
        val latitude: Double,
        val longitude: Double,
        val targetNorthMeters: Double,
        val targetEastMeters: Double,
        val targetUpMeters: Double,
        val headingDegrees: Double?,
        val requiresRtkFixed: Boolean,
        val requiresSimulation: Boolean,
        val deadlineMs: Long,
    )

    private var target: Target? = null
    private var mode = PositionClosureMode.GPS_RTK
    private var estimatedNorthMeters = 0.0
    private var estimatedEastMeters = 0.0
    private var lastVelocityTimestampMs: Long? = null
    private var estimatedUpMeters = 0.0
    private var verticalCompleted = true
    private var lastVerticalVelocityTimestampMs: Long? = null
    private var lastAppliedCommand = ControlVelocityCommand(0.0, 0.0, 0.0, 0.0, 1.0, "position reset")

    fun start(command: ControlVelocityCommand, pose: PositionControlPose, nowMs: Long): String? {
        val forward = command.targetForwardMeters ?: return "model output has no relative forward target"
        val right = command.targetRightMeters ?: return "model output has no relative right target"
        val up = command.targetUpMeters ?: return "model output has no relative altitude target"
        if (!forward.isFinite() || !right.isFinite() || !up.isFinite()) return "position target contains invalid values"
        if (abs(up) > MAX_RELATIVE_VERTICAL_METERS) {
            return "relative vertical target exceeds ±${"%.1f".format(MAX_RELATIVE_VERTICAL_METERS)}m limit"
        }
        if (up < -ZERO_VERTICAL_COMPLETION_EPSILON_METERS) {
            val currentHeight = pose.downwardHeightMeters
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: pose.relativeAltitudeMeters
            if (!currentHeight.isFinite() || currentHeight + up < MIN_DESCENT_TARGET_HEIGHT_METERS) {
                return "vertical descent target would be below ${"%.1f".format(MIN_DESCENT_TARGET_HEIGHT_METERS)}m safety height"
            }
        }
        val validationIssue = pose.validationIssue(mode, nowMs, maxPoseAgeMs)
        if (validationIssue != null) return validationIssue
        if (abs(up) > ZERO_VERTICAL_COMPLETION_EPSILON_METERS) {
            pose.verticalVelocityIssue(nowMs, maxPoseAgeMs)?.let { return it }
        }

        val headingRad = Math.toRadians(pose.headingDegrees)
        val northMeters = forward * cos(headingRad) - right * sin(headingRad)
        val eastMeters = forward * sin(headingRad) + right * cos(headingRad)
        val targetLatitude = pose.latitude + Math.toDegrees(northMeters / EARTH_RADIUS_METERS)
        val longitudeScale = cos(Math.toRadians(pose.latitude)).coerceAtLeast(0.01)
        val targetLongitude = pose.longitude + Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * longitudeScale))
        val planar = hypot(forward, right)
        val maximumDistance = if (mode == PositionClosureMode.VELOCITY_ESTIMATE) {
            MAX_VELOCITY_ESTIMATE_DISTANCE_METERS
        } else {
            MAX_GPS_ACTION_DISTANCE_METERS
        }
        if (!planar.isFinite() || planar > maximumDistance) {
            return "position target exceeds ${"%.1f".format(maximumDistance)}m ${mode.label(appContext)} limit"
        }
        val targetHeading = if (planar >= 0.05 && abs(forward) > 1e-6) {
            normalizeHeading(pose.headingDegrees + Math.toDegrees(atan2(right, forward)))
        } else {
            null
        }
        val horizontalTravelMs = if (mode == PositionClosureMode.VELOCITY_ESTIMATE) {
            (1_500.0 + planar / VELOCITY_ESTIMATE_MAX_SPEED * 1_000.0)
                .toLong()
        } else {
            (2_000.0 + planar / 0.5 * 1_000.0).toLong()
        }
        val verticalTravelMs = (2_000.0 + abs(up) / EXPECTED_VERTICAL_PROGRESS_SPEED * 1_000.0).toLong()
        val timeoutLimit = if (mode == PositionClosureMode.VELOCITY_ESTIMATE) {
            VELOCITY_ESTIMATE_MAX_TIMEOUT_MS
        } else {
            maxActionTimeoutMs
        }
        val expectedTravelMs = maxOf(horizontalTravelMs, verticalTravelMs).coerceAtMost(timeoutLimit)
        target = Target(
            mode = mode,
            latitude = targetLatitude,
            longitude = targetLongitude,
            targetNorthMeters = northMeters,
            targetEastMeters = eastMeters,
            targetUpMeters = up,
            headingDegrees = targetHeading,
            requiresRtkFixed = mode == PositionClosureMode.GPS_RTK && pose.rtkFixed,
            requiresSimulation = pose.simulated,
            deadlineMs = nowMs + expectedTravelMs,
        )
        estimatedNorthMeters = 0.0
        estimatedEastMeters = 0.0
        lastVelocityTimestampMs = pose.velocityTimestampMs
        estimatedUpMeters = 0.0
        verticalCompleted = abs(up) <= ZERO_VERTICAL_COMPLETION_EPSILON_METERS
        lastVerticalVelocityTimestampMs = pose.verticalVelocityTimestampMs
        lastAppliedCommand = ControlVelocityCommand(0.0, 0.0, 0.0, 0.0, 1.0, "position start")
        return null
    }

    fun step(pose: PositionControlPose?, nowMs: Long): Step {
        val currentTarget = target ?: return terminal("position target is not active", false)
        val validationIssue = pose?.validationIssue(currentTarget.mode, nowMs, maxPoseAgeMs)
        if (pose == null || validationIssue != null) {
            target = null
            return terminal(validationIssue ?: "position telemetry lost or stale", false)
        }
        if (currentTarget.requiresRtkFixed && !pose.rtkFixed) {
            target = null
            return terminal("RTK fixed solution lost", false)
        }
        if (currentTarget.requiresSimulation && !pose.simulated) {
            target = null
            return terminal("simulator position source lost", false)
        }
        if (!verticalCompleted) {
            val verticalIssue = pose.verticalVelocityIssue(nowMs, maxPoseAgeMs)
            if (verticalIssue != null) {
                target = null
                return terminal(verticalIssue, false)
            }
            integrateVerticalVelocity(pose)
        }
        if (nowMs >= currentTarget.deadlineMs) {
            target = null
            return terminal("position action timeout", false)
        }

        val northError: Double
        val eastError: Double
        if (currentTarget.mode == PositionClosureMode.VELOCITY_ESTIMATE) {
            integrateVelocityEstimate(pose)
            northError = currentTarget.targetNorthMeters - estimatedNorthMeters
            eastError = currentTarget.targetEastMeters - estimatedEastMeters
        } else {
            northError = Math.toRadians(currentTarget.latitude - pose.latitude) * EARTH_RADIUS_METERS
            val meanLatitude = Math.toRadians((currentTarget.latitude + pose.latitude) * 0.5)
            eastError = Math.toRadians(currentTarget.longitude - pose.longitude) *
                EARTH_RADIUS_METERS * cos(meanLatitude)
        }
        val horizontalError = hypot(northError, eastError)
        val headingRad = Math.toRadians(pose.headingDegrees)
        val forwardError = northError * cos(headingRad) + eastError * sin(headingRad)
        val rightError = -northError * sin(headingRad) + eastError * cos(headingRad)
        val verticalError = currentTarget.targetUpMeters - estimatedUpMeters
        val yawError = currentTarget.headingDegrees?.let { shortestAngleDegrees(it - pose.headingDegrees) } ?: 0.0
        val highPrecision = currentTarget.mode == PositionClosureMode.GPS_RTK && (pose.rtkFixed || pose.simulated)
        val positionTolerance = when {
            currentTarget.mode == PositionClosureMode.VELOCITY_ESTIMATE -> VELOCITY_ESTIMATE_TOLERANCE_METERS
            highPrecision -> rtkPositionToleranceMeters
            else -> gpsPositionToleranceMeters
        }
        if (!verticalCompleted &&
            abs(verticalError) <= altitudeToleranceMeters.coerceAtMost(VERTICAL_COMPLETION_TOLERANCE_METERS) &&
            abs(pose.verticalSpeedUpMetersPerSecond ?: Double.POSITIVE_INFINITY) <= VERTICAL_SETTLED_SPEED_METERS_PER_SECOND) {
            verticalCompleted = true
        }
        val arrived = horizontalError <= positionTolerance && verticalCompleted &&
            abs(yawError) <= yawToleranceDegrees
        if (arrived) {
            target = null
            return terminal("position target reached", true, horizontalError, verticalError)
        }

        val kp = when {
            currentTarget.mode == PositionClosureMode.VELOCITY_ESTIMATE -> VELOCITY_ESTIMATE_KP
            highPrecision -> rtkKp
            else -> gpsKp
        }
        val maxHorizontalSpeed = requestedMaxHorizontalSpeed
        val forwardVelocity = (forwardError * kp).coerceIn(-maxHorizontalSpeed, maxHorizontalSpeed)
        val rightVelocity = (rightError * kp).coerceIn(-maxHorizontalSpeed, maxHorizontalSpeed)
        val norm = hypot(forwardVelocity, rightVelocity)
        val scale = if (norm > maxHorizontalSpeed) maxHorizontalSpeed / norm else 1.0
        val command = ControlVelocityCommand(
            vxMetersPerSecond = forwardVelocity * scale,
            vyMetersPerSecond = rightVelocity * scale,
            vzMetersPerSecond = verticalVelocityCommand(verticalError),
            yawRateDegreesPerSecond = (yawError * kp).coerceIn(-maxYawRateDegrees, maxYawRateDegrees),
            confidence = 1.0,
            reason = "${currentTarget.mode.label(appContext)} ${pose.source} planar=${"%.2f".format(horizontalError)}m vertical=${"%.2f".format(verticalError)}m",
        )
        return Step(command, false, false, command.reason, horizontalError, verticalError)
    }

    fun cancel() {
        target = null
        estimatedNorthMeters = 0.0
        estimatedEastMeters = 0.0
        lastVelocityTimestampMs = null
        estimatedUpMeters = 0.0
        verticalCompleted = true
        lastVerticalVelocityTimestampMs = null
        lastAppliedCommand = ControlVelocityCommand(0.0, 0.0, 0.0, 0.0, 1.0, "position cancel")
    }

    fun isActive(): Boolean = target != null

    fun setMaximumHorizontalSpeed(metersPerSecond: Double) {
        requestedMaxHorizontalSpeed = HorizontalSpeedSetting.normalize(metersPerSecond)
    }

    fun setMode(newMode: PositionClosureMode) {
        if (newMode == mode) return
        cancel()
        mode = newMode
    }

    fun currentMode(): PositionClosureMode = mode

    fun readinessIssue(
        pose: PositionControlPose?,
        nowMs: Long,
        requestedMode: PositionClosureMode = mode,
    ): String? = if (pose == null) {
        "position telemetry is unavailable"
    } else {
        pose.validationIssue(requestedMode, nowMs, maxPoseAgeMs)
    }

    fun recordAppliedCommand(command: ControlVelocityCommand) {
        if (target?.mode == PositionClosureMode.VELOCITY_ESTIMATE) lastAppliedCommand = command
    }

    private fun integrateVelocityEstimate(pose: PositionControlPose) {
        val timestamp = pose.velocityTimestampMs ?: return
        val previous = lastVelocityTimestampMs
        lastVelocityTimestampMs = timestamp
        if (previous == null || timestamp <= previous) return
        val deltaSeconds = ((timestamp - previous).coerceAtMost(MAX_INTEGRATION_STEP_MS)) / 1_000.0
        val speed = pose.horizontalSpeedMetersPerSecond ?: return
        val commandMagnitude = hypot(lastAppliedCommand.vxMetersPerSecond, lastAppliedCommand.vyMetersPerSecond)
        if (commandMagnitude <= 0.01 || speed <= 0.01) return
        val bodyForward = lastAppliedCommand.vxMetersPerSecond / commandMagnitude
        val bodyRight = lastAppliedCommand.vyMetersPerSecond / commandMagnitude
        val heading = Math.toRadians(pose.headingDegrees)
        val northDirection = bodyForward * cos(heading) - bodyRight * sin(heading)
        val eastDirection = bodyForward * sin(heading) + bodyRight * cos(heading)
        estimatedNorthMeters += speed * northDirection * deltaSeconds
        estimatedEastMeters += speed * eastDirection * deltaSeconds
    }

    private fun integrateVerticalVelocity(pose: PositionControlPose) {
        val timestamp = pose.verticalVelocityTimestampMs ?: return
        val previous = lastVerticalVelocityTimestampMs
        lastVerticalVelocityTimestampMs = timestamp
        if (previous == null || timestamp <= previous) return
        val deltaSeconds = ((timestamp - previous).coerceAtMost(MAX_INTEGRATION_STEP_MS)) / 1_000.0
        val measuredUp = pose.verticalSpeedUpMetersPerSecond ?: return
        val filtered = if (abs(measuredUp) < VERTICAL_SPEED_DEADBAND_METERS_PER_SECOND) {
            0.0
        } else {
            measuredUp.coerceIn(-MAX_VALID_VERTICAL_SPEED, MAX_VALID_VERTICAL_SPEED)
        }
        estimatedUpMeters += filtered * deltaSeconds
    }

    private fun verticalVelocityCommand(verticalError: Double): Double {
        if (verticalCompleted || abs(verticalError) <= VERTICAL_COMPLETION_TOLERANCE_METERS) return 0.0
        val proportional = (verticalError * VERTICAL_KP).coerceIn(-maxVerticalSpeed, maxVerticalSpeed)
        val magnitude = abs(proportional).coerceAtLeast(MIN_ACTIVE_VERTICAL_SPEED)
        return if (verticalError < 0.0) -magnitude else magnitude
    }

    private fun terminal(
        reason: String,
        successful: Boolean,
        error: Double = 0.0,
        verticalError: Double = 0.0,
    ): Step = Step(
        command = ControlVelocityCommand(0.0, 0.0, 0.0, 0.0, 1.0, reason),
        terminal = true,
        successful = successful,
        reason = reason,
        horizontalErrorMeters = error,
        verticalErrorMeters = verticalError,
    )

    private fun PositionControlPose.validationIssue(
        requestedMode: PositionClosureMode,
        nowMs: Long,
        maxAgeMs: Long,
    ): String? {
        if (!relativeAltitudeMeters.isFinite() || !headingDegrees.isFinite() || nowMs - timestampMs !in 0..maxAgeMs) {
            return "position attitude/altitude telemetry is invalid or stale"
        }
        if (requestedMode == PositionClosureMode.GPS_RTK) {
            if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
                (abs(latitude) < 0.000001 && abs(longitude) < 0.000001) || nowMs - positionTimestampMs !in 0..maxAgeMs) {
                return "GPS/RTK position is invalid or stale"
            }
        } else {
            val speed = horizontalSpeedMetersPerSecond
            val velocityAge = velocityTimestampMs?.let { nowMs - it }
            if (speed == null || !speed.isFinite() || speed !in 0.0..MAX_VALID_ESTIMATE_SPEED || velocityAge !in 0..maxAgeMs) {
                return "velocity integration telemetry is invalid or stale"
            }
        }
        return null
    }

    private fun PositionControlPose.verticalVelocityIssue(nowMs: Long, maxAgeMs: Long): String? {
        val speed = verticalSpeedUpMetersPerSecond
        val age = verticalVelocityTimestampMs?.let { nowMs - it }
        return if (speed == null || !speed.isFinite() || abs(speed) > MAX_VALID_VERTICAL_SPEED || age !in 0..maxAgeMs) {
            "vertical velocity telemetry is invalid or stale"
        } else {
            null
        }
    }

    private fun normalizeHeading(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

    private fun shortestAngleDegrees(degrees: Double): Double = ((degrees + 540.0) % 360.0) - 180.0

    private companion object {
        const val EARTH_RADIUS_METERS = 6_378_137.0
        const val MAX_RELATIVE_VERTICAL_METERS = 0.5
        const val ZERO_VERTICAL_COMPLETION_EPSILON_METERS = 0.05
        const val MIN_DESCENT_TARGET_HEIGHT_METERS = 0.8
        const val VERTICAL_COMPLETION_TOLERANCE_METERS = 0.10
        const val VERTICAL_SETTLED_SPEED_METERS_PER_SECOND = 0.12
        const val VERTICAL_SPEED_DEADBAND_METERS_PER_SECOND = 0.03
        const val MIN_ACTIVE_VERTICAL_SPEED = 0.12
        const val VERTICAL_KP = 0.7
        const val EXPECTED_VERTICAL_PROGRESS_SPEED = 0.14
        const val MAX_VALID_VERTICAL_SPEED = 2.0
        const val MAX_GPS_ACTION_DISTANCE_METERS = 10.0
        const val MAX_VELOCITY_ESTIMATE_DISTANCE_METERS = 2.0
        const val VELOCITY_ESTIMATE_MAX_SPEED = 0.6
        const val VELOCITY_ESTIMATE_KP = 0.8
        const val VELOCITY_ESTIMATE_TOLERANCE_METERS = 0.25
        const val VELOCITY_ESTIMATE_MAX_TIMEOUT_MS = 4_000L
        const val MAX_INTEGRATION_STEP_MS = 250L
        const val MAX_VALID_ESTIMATE_SPEED = 3.0
    }
}
