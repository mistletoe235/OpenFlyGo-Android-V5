package edu.playground.djivln.survey

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.wayline.WaylinePhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiWaylineTelemetryPolicyTest {
    private val received = 10_000_000_000L

    @Test fun rthAndLandingAreIndependentInterruptionEvidenceWithoutMissionCallbacks() {
        for (mode in listOf("GoHome", "GO_HOME", "Landing")) {
            assertTrue(DjiWaylineTelemetryPolicy.confirmsInterruption(
                WaylinePhase.EXECUTING,
                AircraftSnapshot(connected = true, isFlying = true, flightMode = mode, flightStateUpdatedAtNanos = received),
                received + 100_000_000,
            ))
        }
    }

    @Test fun cancelledRthInManualFlightCanReconcileRejectedPauseButNormalStartCannot() {
        val manual = AircraftSnapshot(connected = true, isFlying = true, flightMode = "P-GPS", flightStateUpdatedAtNanos = received)
        assertTrue(DjiWaylineTelemetryPolicy.confirmsInterruption(WaylinePhase.EXECUTING, manual, received, pauseRejected = true))
        assertFalse(DjiWaylineTelemetryPolicy.confirmsInterruption(WaylinePhase.EXECUTING, manual, received))
        for (phase in listOf(WaylinePhase.PREPARING, WaylinePhase.RECOVERING, WaylinePhase.IDLE, WaylinePhase.READY)) {
            assertFalse(DjiWaylineTelemetryPolicy.confirmsInterruption(phase, manual, received, pauseRejected = true))
        }
        assertFalse(DjiWaylineTelemetryPolicy.confirmsInterruption(
            WaylinePhase.EXECUTING, manual.copy(flightMode = "F-WP"), received, pauseRejected = true,
        ))
    }

    @Test fun unknownDisconnectedStaleOrFutureFlightStateCannotBeTreatedAsSuccessfulInterruption() {
        val aircraft = AircraftSnapshot(connected = true, isFlying = true, flightMode = "GoHome", flightStateUpdatedAtNanos = received)
        for (invalid in listOf(
            aircraft.copy(connected = false),
            aircraft.copy(flightStateUpdatedAtNanos = 0),
            aircraft.copy(flightMode = null),
            aircraft.copy(flightMode = "UNKNOWN"),
        )) {
            assertFalse(DjiWaylineTelemetryPolicy.confirmsInterruption(WaylinePhase.EXECUTING, invalid, received, pauseRejected = true))
        }
        assertFalse(DjiWaylineTelemetryPolicy.confirmsInterruption(WaylinePhase.EXECUTING, aircraft, received - 1))
        assertFalse(DjiWaylineTelemetryPolicy.confirmsInterruption(WaylinePhase.EXECUTING, aircraft, received + 2_000_000_001))
        assertFalse(DjiWaylineTelemetryPolicy.confirmsInterruption(
            WaylinePhase.EXECUTING, aircraft.copy(flightMode = "F-WP", goHomeState = "UNKNOWN"), received,
        ))
    }

    @Test fun controllerReconcilesBeforeCaptureAndDoesNotWaitForFailedPauseToChangeState() {
        val source = java.io.File("src/main/java/edu/playground/djivln/ui/SurveyFeatureController.kt").readText()
        val capture = source.substringAfter("private fun updateDjiAppCapture() {").substringBefore("val location =")
        assertTrue(capture.indexOf("confirmsInterruption") < capture.indexOf("confirmsExecution"))
        val reconcile = source.substringAfter("private fun reconcileDjiInterruption(").substringBefore("private fun preserveInterruptedDjiRecovery")
        assertTrue(reconcile.contains("stopDjiAppCapture()"))
        assertTrue(reconcile.contains("waylinePort.confirmInterruptionFromTelemetry()"))
        val pause = source.substringAfter("private fun pauseDjiAndPersistRecovery(").substringBefore("private fun queryPausedBreakpointWithRetry")
        assertTrue(pause.contains("reconcileDjiInterruption(reason, pauseRejected = true)"))
        assertTrue(pause.contains("preserveInterruptedDjiRecovery(reason, completion)"))
    }

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
