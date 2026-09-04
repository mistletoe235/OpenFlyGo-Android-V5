package edu.playground.djivln.ui.widgets

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightEnduranceBarPolicyTest {
    @Test
    fun `normalizes supported DJI endurance values`() {
        val state = FlightEnduranceBarPolicy.from(
            AircraftSnapshot(
                connected = true,
                aircraftBatteryPercent = 92,
                batteryNeededToGoHomePercent = 24,
                batteryNeededToLandPercent = 8,
                remainingFlightTimeSeconds = 754,
            ),
        )

        assertEquals(92, state.remainingChargePercent)
        assertEquals(24, state.batteryNeededToGoHomePercent)
        assertEquals(8, state.batteryNeededToLandPercent)
        assertEquals("12:34", state.timeLabel)
    }

    @Test
    fun `keeps connected ground bar visible without fabricated estimates`() {
        val state = FlightEnduranceBarPolicy.from(
            AircraftSnapshot(connected = true, aircraftBatteryPercent = 100),
        )

        assertEquals("--:--", state.timeLabel)
        assertNull(state.batteryNeededToGoHomePercent)
        assertNull(state.batteryNeededToLandPercent)
    }

    @Test
    fun `rejects invalid percentages and formats hours`() {
        val state = FlightEnduranceBarPolicy.from(
            AircraftSnapshot(
                connected = true,
                aircraftBatteryPercent = 120,
                batteryNeededToGoHomePercent = -1,
                batteryNeededToLandPercent = 101,
                remainingFlightTimeSeconds = 3_661,
            ),
        )

        assertEquals(0, state.remainingChargePercent)
        assertNull(state.batteryNeededToGoHomePercent)
        assertNull(state.batteryNeededToLandPercent)
        assertEquals("1:01:01", state.timeLabel)
    }
}
