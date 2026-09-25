package edu.playground.djivln.survey

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.telemetry.VelocityMetersPerSecond
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovingCapturePoseGateTest {
    private val target = SurveyWaypoint(
        point = GeoPoint(31.0, 121.0, 40.0),
        headingDegrees = 90.0,
        gimbalPitchDegrees = -90.0,
        kind = SurveyWaypointKind.PASS_START,
        captureAction = CaptureAction.START_DISTANCE_INTERVAL,
        captureIntervalMeters = 10.0,
        passIndex = 0,
    )

    private fun aircraft(now: Long, heading: Double = 90.0) = AircraftSnapshot(
        connected = true,
        aircraftLocationUpdatedAtNanos = now,
        relativeAltitudeMeters = 40.0,
        relativeAltitudeUpdatedAtNanos = now,
        headingDegrees = heading,
        headingUpdatedAtNanos = now,
        gimbalPitchDegrees = -90.0,
        gimbalAttitudeUpdatedAtNanos = now,
        velocity = VelocityMetersPerSecond(0.0, 4.88, 0.0),
        velocityUpdatedAtNanos = now,
    )

    @Test fun headingMustAlignBeforeFirstPhotoEvenAfterSixtySeconds() {
        val gate = MovingCapturePoseGate()
        for (now in 1_000_000_000L..61_000_000_000L step 100_000_000L) {
            assertFalse(gate.ready(aircraft(now, -55.8), target, true, now))
        }
        assertFalse(gate.ready(aircraft(61_100_000_000L), target, true, 61_100_000_000L))
        assertFalse(gate.ready(aircraft(61_899_000_000L), target, true, 61_899_000_000L))
        assertTrue(gate.ready(aircraft(61_900_000_000L), target, true, 61_900_000_000L))
    }

    @Test fun headingOvershootRestartsDwellWithoutRequiringStoppedVelocity() {
        val gate = MovingCapturePoseGate()
        assertFalse(gate.ready(aircraft(1_000_000_000L), target, true, 1_000_000_000L))
        assertFalse(gate.ready(aircraft(1_500_000_000L, 94.0), target, true, 1_500_000_000L))
        assertFalse(gate.ready(aircraft(1_600_000_000L), target, true, 1_600_000_000L))
        assertFalse(gate.ready(aircraft(2_399_000_000L), target, true, 2_399_000_000L))
        assertTrue(gate.ready(aircraft(2_400_000_000L), target, true, 2_400_000_000L))
    }

    @Test fun staleHeadingAndUnverifiedGimbalCannotReleaseCapture() {
        val gate = MovingCapturePoseGate()
        assertFalse(gate.ready(aircraft(1_000_000_000L), target, true, 1_000_000_000L))
        assertFalse(gate.ready(aircraft(1_900_000_000L).copy(headingUpdatedAtNanos = 1L), target, true, 1_900_000_000L))
        assertFalse(gate.ready(aircraft(2_000_000_000L), target, true, 2_000_000_000L))
        assertFalse(gate.ready(aircraft(2_800_000_000L), target, false, 2_800_000_000L))
        assertFalse(gate.ready(aircraft(2_900_000_000L), target, true, 2_900_000_000L))
        assertTrue(gate.ready(aircraft(3_700_000_000L), target, true, 3_700_000_000L))
    }

    @Test fun targetChangeResetAndLongUpdateGapRequireNewDwell() {
        val gate = MovingCapturePoseGate()
        assertFalse(gate.ready(aircraft(1_000_000_000L), target, true, 1_000_000_000L))
        assertTrue(gate.ready(aircraft(1_800_000_000L), target, true, 1_800_000_000L))
        val next = target.copy(passIndex = 1)
        assertFalse(gate.ready(aircraft(1_900_000_000L), next, true, 1_900_000_000L))
        assertTrue(gate.ready(aircraft(2_700_000_000L), next, true, 2_700_000_000L))
        assertFalse(gate.ready(aircraft(4_000_000_000L), next, true, 4_000_000_000L))
        assertTrue(gate.ready(aircraft(4_800_000_000L), next, true, 4_800_000_000L))
        gate.reset()
        assertFalse(gate.ready(aircraft(4_900_000_000L), next, true, 4_900_000_000L))
    }

    @Test fun headingWraparoundIsAcceptedButMissingTargetIsNot() {
        val gate = MovingCapturePoseGate()
        val northTarget = target.copy(headingDegrees = 1.0)
        assertFalse(gate.ready(aircraft(1_000_000_000L, 359.0), northTarget, true, 1_000_000_000L))
        assertTrue(gate.ready(aircraft(1_800_000_000L, 359.0), northTarget, true, 1_800_000_000L))
        assertFalse(gate.ready(aircraft(1_900_000_000L), null, true, 1_900_000_000L))
    }
}
