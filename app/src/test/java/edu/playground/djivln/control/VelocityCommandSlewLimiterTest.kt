package edu.playground.djivln.control

import edu.playground.djivln.control.ControlVelocityCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class VelocityCommandSlewLimiterTest {
    private fun command(forward: Double, right: Double = 0.0, up: Double = 0.0, yaw: Double = 0.0) =
        ControlVelocityCommand(forward, right, up, yaw, 1.0, "test")

    @Test fun limitsHorizontalVectorAcceleration() {
        val limiter = VelocityCommandSlewLimiter()

        val first = limiter.limit(command(2.0, 2.0), timestampMs = 1_000L)
        assertEquals(0.032, hypot(first.vxMetersPerSecond, first.vyMetersPerSecond), 0.0001)
        val second = limiter.limit(command(2.0, 2.0), timestampMs = 1_100L)
        assertEquals(0.112, hypot(second.vxMetersPerSecond, second.vyMetersPerSecond), 0.0001)
    }

    @Test fun reversalMustRampThroughZero() {
        val limiter = VelocityCommandSlewLimiter()
        limiter.limit(command(2.0), timestampMs = 1_000L)
        val forward = limiter.limit(command(2.0), timestampMs = 1_250L)
        val reversing = limiter.limit(command(-2.0), timestampMs = 1_500L)

        assertEquals(0.232, forward.vxMetersPerSecond, 0.0001)
        assertEquals(0.032, reversing.vxMetersPerSecond, 0.0001)
        assertTrue(reversing.vxMetersPerSecond > 0.0)
    }

    @Test fun limitsVerticalAndYawAcceleration() {
        val limiter = VelocityCommandSlewLimiter()
        val limited = limiter.limit(command(0.0, up = 1.0, yaw = 90.0), timestampMs = 1_000L)

        assertEquals(0.02, limited.vzMetersPerSecond, 0.0001)
        assertEquals(1.2, limited.yawRateDegreesPerSecond, 0.0001)
    }

    @Test fun emergencyResetBypassesRampAndReturnsExactZero() {
        val limiter = VelocityCommandSlewLimiter()
        limiter.limit(command(2.0, up = 1.0, yaw = 90.0), timestampMs = 1_000L)

        limiter.reset(timestampMs = 1_010L)

        assertEquals(0.0, limiter.current().vxMetersPerSecond, 0.0)
        assertEquals(0.0, limiter.current().vzMetersPerSecond, 0.0)
        assertEquals(0.0, limiter.current().yawRateDegreesPerSecond, 0.0)
    }
}
