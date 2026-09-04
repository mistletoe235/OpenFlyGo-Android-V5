package edu.playground.djivln.hil

import org.junit.Assert.assertEquals
import org.junit.Test

class DjiV5SimulatorCoordinatesTest {
    @Test
    fun convertsSdkLocalFrameToProtocolEnuFru() {
        val value = DjiV5SimulatorCoordinates.fromSdk(
            positionX = 12.0,
            positionY = -3.5,
            positionZ = -8.0,
            roll = 4.0,
            pitch = -6.0,
        )
        assertEquals(-3.5, value.eastMeters, 0.0)
        assertEquals(12.0, value.northMeters, 0.0)
        assertEquals(8.0, value.upMeters, 0.0)
        assertEquals(4.0, value.rollDegrees, 0.0)
        assertEquals(6.0, value.pitchDegrees, 0.0)
    }
}
