package edu.playground.djivln.survey

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.wayline.WaylinePhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiWaylineTelemetryPolicyTest {
    @Test fun `active flying F-WP confirms missing preparing callback`() {
        assertTrue(
            DjiWaylineTelemetryPolicy.confirmsExecution(
                captureArmed = true,
                phase = WaylinePhase.PREPARING,
                aircraft = AircraftSnapshot(connected = true, isFlying = true, flightMode = "F-WP"),
            ),
        )
    }

    @Test fun `ordinary flight cannot unlock wayline capture`() {
        assertFalse(
            DjiWaylineTelemetryPolicy.confirmsExecution(
                captureArmed = true,
                phase = WaylinePhase.PREPARING,
                aircraft = AircraftSnapshot(connected = true, isFlying = true, flightMode = "GPS_NORMAL"),
            ),
        )
    }

    @Test fun `waypoint mode without this app arming capture cannot unlock it`() {
        assertFalse(
            DjiWaylineTelemetryPolicy.confirmsExecution(
                captureArmed = false,
                phase = WaylinePhase.PREPARING,
                aircraft = AircraftSnapshot(connected = true, isFlying = true, flightMode = "F-WP"),
            ),
        )
    }

    @Test fun `grounded waypoint label cannot unlock capture`() {
        assertFalse(
            DjiWaylineTelemetryPolicy.confirmsExecution(
                captureArmed = true,
                phase = WaylinePhase.PREPARING,
                aircraft = AircraftSnapshot(connected = true, isFlying = false, flightMode = "F-WP"),
            ),
        )
    }
}
