package edu.playground.djivln.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightAssessmentTest {
    @Test fun keepsPlausibleOfficialAssessmentValues() {
        val value = FlightAssessment.sanitized(620, 80, 35, 28, 12, 1_250.5)

        assertEquals(620, value.remainingFlightTimeSeconds)
        assertEquals(80, value.timeNeededToGoHomeSeconds)
        assertEquals(35, value.timeNeededToLandSeconds)
        assertEquals(28, value.batteryNeededToGoHomePercent)
        assertEquals(12, value.batteryNeededToLandPercent)
        assertEquals(1_250.5, value.maxSafeFlightRadiusMeters!!, 0.0)
    }

    @Test fun rejectsSentinelsAndOutOfRangeValues() {
        val value = FlightAssessment.sanitized(0, 86_400, -1, 101, -1, Double.NaN)

        assertNull(value.remainingFlightTimeSeconds)
        assertNull(value.timeNeededToGoHomeSeconds)
        assertNull(value.timeNeededToLandSeconds)
        assertNull(value.batteryNeededToGoHomePercent)
        assertNull(value.batteryNeededToLandPercent)
        assertNull(value.maxSafeFlightRadiusMeters)
    }
}
