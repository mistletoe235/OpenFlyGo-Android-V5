package edu.playground.djivln.domain.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryNormalizerTest {
    @Test
    fun convertsDjiNedVerticalVelocityToUpPositive() {
        assertEquals(
            VelocityMetersPerSecond(north = 2.0, east = -3.0, up = 4.0),
            TelemetryNormalizer.nedVelocity(2.0, -3.0, -4.0)
        )
    }

    @Test
    fun rejectsMissingAndSentinelCoordinates() {
        assertNull(TelemetryNormalizer.geoPoint(0.0, 0.0))
        assertNull(TelemetryNormalizer.geoPoint(91.0, 121.0))
        assertNull(TelemetryNormalizer.geoPoint(null, 121.0))
    }

    @Test
    fun keepsValidCoordinateAndFiniteAltitude() {
        assertEquals(
            GeoPoint(31.025, 121.435, 42.5),
            TelemetryNormalizer.geoPoint(31.025, 121.435, 42.5)
        )
    }
}
