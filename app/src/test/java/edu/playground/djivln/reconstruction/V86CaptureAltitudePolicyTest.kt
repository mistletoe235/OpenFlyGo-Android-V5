package edu.playground.djivln.reconstruction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V86CaptureAltitudePolicyTest {
    @Test
    fun `uses direct aircraft asl when available`() {
        val result = V86CaptureAltitudePolicy.resolve(56.2, 46.7, 10.0, "aircraft_asl")
        assertEquals(56.2, result?.absoluteAltitudeMeters ?: Double.NaN, 1e-9)
        assertEquals("aircraft_asl", result?.source)
    }

    @Test
    fun `preserves a previously derived altitude source`() {
        val result = V86CaptureAltitudePolicy.resolve(
            frameAbsoluteAltitudeMeters = 56.7,
            relativeAltitudeMeters = 46.7,
            takeoffAbsoluteAltitudeMeters = 10.0,
            frameAbsoluteAltitudeSource = "takeoff_asl_plus_relative",
        )
        assertEquals(56.7, result?.absoluteAltitudeMeters ?: Double.NaN, 1e-9)
        assertEquals("takeoff_asl_plus_relative", result?.source)
    }

    @Test
    fun `derives asl from task takeoff reference and relative altitude`() {
        val result = V86CaptureAltitudePolicy.resolve(null, 46.7, 10.0)
        assertEquals(56.7, result?.absoluteAltitudeMeters ?: Double.NaN, 1e-9)
        assertEquals("takeoff_asl_plus_relative", result?.source)
    }

    @Test
    fun `rejects capture when neither absolute nor derivable altitude exists`() {
        assertNull(V86CaptureAltitudePolicy.resolve(null, 46.7, null))
        assertNull(V86CaptureAltitudePolicy.resolve(null, null, 10.0))
    }
}
