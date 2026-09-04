package edu.playground.djivln.hil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorOriginParserTest {
    @Test
    fun parsesValidOrigin() {
        val origin = SimulatorOriginParser.parse("31.1815535", "121.4736515").getOrThrow()

        assertEquals(31.1815535, origin.latitude, 0.0)
        assertEquals(121.4736515, origin.longitude, 0.0)
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        assertTrue(SimulatorOriginParser.parse("91", "121").isFailure)
        assertTrue(SimulatorOriginParser.parse("31", "181").isFailure)
    }

    @Test
    fun rejectsMalformedCoordinates() {
        assertTrue(SimulatorOriginParser.parse("纬度", "121.4").isFailure)
        assertTrue(SimulatorOriginParser.parse("31.1", "").isFailure)
    }
}
