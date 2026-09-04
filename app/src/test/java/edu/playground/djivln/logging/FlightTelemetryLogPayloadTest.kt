package edu.playground.djivln.logging

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.telemetry.AttitudeDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FlightTelemetryLogPayloadTest {
    @Test fun includesReturnHomeAndBatteryAssessmentEvidence() {
        val fields = FlightTelemetryLogPayload.fields(
            AircraftSnapshot(
                flightMode = "GO_HOME",
                goHomeState = "RETURNING_TO_HOME",
                autoRthReason = "OUTOF_CONTROL_GOHOME",
                failsafeActive = true,
                failsafeAction = "GOHOME",
                lowBatteryRthEnabled = true,
                lowBatteryRthState = "COUNTING_DOWN",
                smartRthCountdownSeconds = 8,
                timeNeededToGoHomeSeconds = 42,
                batteryNeededToGoHomePercent = 17,
            ),
        )

        assertEquals("GO_HOME", fields["flight_mode"])
        assertEquals("RETURNING_TO_HOME", fields["go_home_state"])
        assertEquals("OUTOF_CONTROL_GOHOME", fields["auto_rth_reason"])
        assertEquals(true, fields["failsafe_active"])
        assertEquals("GOHOME", fields["failsafe_action"])
        assertEquals(true, fields["low_battery_rth_enabled"])
        assertEquals("COUNTING_DOWN", fields["low_battery_rth_state"])
        assertEquals(8, fields["smart_rth_countdown_s"])
        assertEquals(17, fields["battery_needed_go_home_pct"])
    }

    @Test fun returnHomeStateChangesTransitionSignature() {
        val idle = AircraftSnapshot(goHomeState = "IDLE")
        val returning = idle.copy(goHomeState = "RETURNING_TO_HOME")
        assertNotEquals(
            FlightTelemetryLogPayload.transitionSignature(idle),
            FlightTelemetryLogPayload.transitionSignature(returning),
        )
    }

    @Test fun includesAircraftAndGimbalAttitudeEvidence() {
        val fields = FlightTelemetryLogPayload.fields(
            AircraftSnapshot(
                attitude = AttitudeDegrees(roll = 1.5, pitch = -8.0, yaw = 42.0),
                gimbalPitchDegrees = -43.2,
            ),
        )

        assertEquals(1.5, fields["aircraft_roll_deg"])
        assertEquals(-8.0, fields["aircraft_pitch_deg"])
        assertEquals(42.0, fields["aircraft_yaw_deg"])
        assertEquals(-43.2, fields["gimbal_pitch_deg"])
    }
}
