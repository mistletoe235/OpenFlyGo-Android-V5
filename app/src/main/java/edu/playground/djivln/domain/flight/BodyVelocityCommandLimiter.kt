package edu.playground.djivln.domain.flight

import kotlin.math.hypot

data class BodyVelocityLimits(
    val maxHorizontalMetersPerSecond: Double = 4.0,
    val maxVerticalMetersPerSecond: Double = 1.0,
    val maxYawRateDegreesPerSecond: Double = 30.0,
    val maxHorizontalAcceleration: Double = 0.8,
    val maxVerticalAcceleration: Double = 0.5,
    val maxYawAcceleration: Double = 30.0
)

class BodyVelocityCommandLimiter(
    private val limits: BodyVelocityLimits = BodyVelocityLimits()
) {
    private var current = BodyVelocityCommand.ZERO
    private var lastTimestampMs: Long? = null

    fun reset(timestampMs: Long? = null) {
        current = BodyVelocityCommand.ZERO
        lastTimestampMs = timestampMs
    }

    fun limit(target: BodyVelocityCommand, timestampMs: Long): BodyVelocityCommand {
        val bounded = bound(target)
        val elapsedSeconds = lastTimestampMs
            ?.let { (timestampMs - it).coerceIn(MIN_STEP_MS, MAX_STEP_MS) / 1_000.0 }
            ?: MIN_STEP_MS / 1_000.0
        var forwardDelta = bounded.forwardMetersPerSecond - current.forwardMetersPerSecond
        var rightDelta = bounded.rightMetersPerSecond - current.rightMetersPerSecond
        val horizontalDelta = hypot(forwardDelta, rightDelta)
        val maxHorizontalDelta = limits.maxHorizontalAcceleration * elapsedSeconds
        if (horizontalDelta > maxHorizontalDelta) {
            val scale = maxHorizontalDelta / horizontalDelta
            forwardDelta *= scale
            rightDelta *= scale
        }
        current = BodyVelocityCommand(
            forwardMetersPerSecond = current.forwardMetersPerSecond + forwardDelta,
            rightMetersPerSecond = current.rightMetersPerSecond + rightDelta,
            upMetersPerSecond = approach(
                current.upMetersPerSecond,
                bounded.upMetersPerSecond,
                limits.maxVerticalAcceleration * elapsedSeconds
            ),
            yawRateDegreesPerSecond = approach(
                current.yawRateDegreesPerSecond,
                bounded.yawRateDegreesPerSecond,
                limits.maxYawAcceleration * elapsedSeconds
            )
        )
        lastTimestampMs = timestampMs
        return current
    }

    fun current(): BodyVelocityCommand = current

    private fun bound(command: BodyVelocityCommand): BodyVelocityCommand {
        val horizontalSpeed = hypot(command.forwardMetersPerSecond, command.rightMetersPerSecond)
        val scale = if (horizontalSpeed > limits.maxHorizontalMetersPerSecond) {
            limits.maxHorizontalMetersPerSecond / horizontalSpeed
        } else {
            1.0
        }
        return command.copy(
            forwardMetersPerSecond = command.forwardMetersPerSecond * scale,
            rightMetersPerSecond = command.rightMetersPerSecond * scale,
            upMetersPerSecond = command.upMetersPerSecond.coerceIn(
                -limits.maxVerticalMetersPerSecond,
                limits.maxVerticalMetersPerSecond
            ),
            yawRateDegreesPerSecond = command.yawRateDegreesPerSecond.coerceIn(
                -limits.maxYawRateDegreesPerSecond,
                limits.maxYawRateDegreesPerSecond
            )
        )
    }

    private fun approach(current: Double, target: Double, maxDelta: Double): Double =
        current + (target - current).coerceIn(-maxDelta, maxDelta)

    private companion object {
        const val MIN_STEP_MS = VirtualStickCadence.PERIOD_MILLIS
        const val MAX_STEP_MS = 250L
    }
}
