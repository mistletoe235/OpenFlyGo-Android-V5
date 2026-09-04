package edu.playground.djivln.control

import org.junit.Assert.assertEquals
import org.junit.Test

class BodyVelocityToDjiAxesTest {
    @Test fun mapsBodyFruToDjiBodyVelocityFields() {
        val axes = BodyVelocityToDjiAxes.map(
            forward = 2.0,
            right = -0.4,
            up = 0.3,
            yawRateDegreesPerSecond = 12.0,
        )

        assertEquals(-0.4, axes.pitch, 0.0)
        assertEquals(2.0, axes.roll, 0.0)
        assertEquals(0.3, axes.verticalThrottle, 0.0)
        assertEquals(12.0, axes.yaw, 0.0)
    }
}
