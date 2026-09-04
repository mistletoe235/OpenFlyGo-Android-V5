package edu.playground.djivln.control

import edu.playground.djivln.control.ControlVelocityCommand
import edu.playground.djivln.domain.flight.VirtualStickCadence
import kotlin.math.hypot

class VelocityCommandSlewLimiter(
    private val maxHorizontalAcceleration: Double = 0.8,
    private val maxVerticalAcceleration: Double = 0.5,
    private val maxYawAcceleration: Double = 30.0,
) {
    private var current = zeroCommand()
    private var lastTimestampMs: Long? = null

    init {
        require(maxHorizontalAcceleration > 0.0)
        require(maxVerticalAcceleration > 0.0)
        require(maxYawAcceleration > 0.0)
    }

    fun reset(timestampMs: Long? = null) {
        current = zeroCommand()
        lastTimestampMs = timestampMs
    }

    fun limit(target: ControlVelocityCommand, timestampMs: Long): ControlVelocityCommand {
        val elapsedSeconds = lastTimestampMs
            ?.let { (timestampMs - it).coerceIn(MIN_STEP_MS, MAX_STEP_MS) / 1_000.0 }
            ?: MIN_STEP_MS / 1_000.0

        var forwardDelta = target.vxMetersPerSecond - current.vxMetersPerSecond
        var rightDelta = target.vyMetersPerSecond - current.vyMetersPerSecond
        val horizontalDelta = hypot(forwardDelta, rightDelta)
        val maxHorizontalDelta = maxHorizontalAcceleration * elapsedSeconds
        if (horizontalDelta > maxHorizontalDelta) {
            val scale = maxHorizontalDelta / horizontalDelta
            forwardDelta *= scale
            rightDelta *= scale
        }

        current = target.copy(
            vxMetersPerSecond = current.vxMetersPerSecond + forwardDelta,
            vyMetersPerSecond = current.vyMetersPerSecond + rightDelta,
            vzMetersPerSecond = approach(
                current.vzMetersPerSecond,
                target.vzMetersPerSecond,
                maxVerticalAcceleration * elapsedSeconds,
            ),
            yawRateDegreesPerSecond = approach(
                current.yawRateDegreesPerSecond,
                target.yawRateDegreesPerSecond,
                maxYawAcceleration * elapsedSeconds,
            ),
        )
        lastTimestampMs = timestampMs
        return current
    }

    fun current(): ControlVelocityCommand = current

    private fun approach(current: Double, target: Double, maxDelta: Double): Double =
        current + (target - current).coerceIn(-maxDelta, maxDelta)

    private companion object {
        const val MIN_STEP_MS = VirtualStickCadence.PERIOD_MILLIS
        const val MAX_STEP_MS = 250L

        fun zeroCommand() = ControlVelocityCommand(0.0, 0.0, 0.0, 0.0, 1.0, "slew reset")
    }
}
