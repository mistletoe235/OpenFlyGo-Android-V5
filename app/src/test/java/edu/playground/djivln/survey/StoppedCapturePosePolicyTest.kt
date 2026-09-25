package edu.playground.djivln.survey

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoppedCapturePosePolicyTest {
    private val nowNanos = 100_000_000_000L
    private val point = GeoPoint(31.0, 121.0, 60.0)
    private val target = SurveyWaypoint(
        point = point,
        headingDegrees = 87.9,
        gimbalPitchDegrees = -90.0,
        kind = SurveyWaypointKind.CAPTURE_POINT,
        captureAction = CaptureAction.CAPTURE_ON_REACH,
        passIndex = 0,
    )
    private val aircraft = AircraftSnapshot(
        connected = true,
        aircraftLocationUpdatedAtNanos = nowNanos,
        relativeAltitudeMeters = 60.0,
        relativeAltitudeUpdatedAtNanos = nowNanos,
        headingDegrees = 87.9,
        headingUpdatedAtNanos = nowNanos,
        gimbalPitchDegrees = -90.0,
        gimbalAttitudeUpdatedAtNanos = nowNanos,
        velocityUpdatedAtNanos = nowNanos,
    )

    @Test fun transitLongerThanTwentySecondsDoesNotStartPoseTimeout() {
        val farAway = point.copy(latitude = point.latitude + 0.001)
        var startedAt = 0L
        for (elapsedMillis in 1_000L..61_000L step 1_000L) {
            startedAt = StoppedCapturePosePolicy.verificationStartedAt(
                StoppedCapturePosePolicy.arrived(aircraft, target, farAway, nowNanos),
                startedAt, elapsedMillis,
            )
            assertFalse(SurveyGimbalSettlePolicy.hasTimedOut(elapsedMillis, startedAt))
        }
        assertEquals(0L, startedAt)
    }

    @Test fun arrivalStartsTimeoutEvenWithWrongHeadingAndHighSpeed() {
        val misaligned = aircraft.copy(headingDegrees = -55.8)
        val arrived = StoppedCapturePosePolicy.arrived(misaligned, target, point, nowNanos)
        assertTrue(arrived)
        assertFalse(StoppedCapturePosePolicy.aligned(misaligned, target, point, 3.245, nowNanos))
        val startedAt = StoppedCapturePosePolicy.verificationStartedAt(arrived, 0L, 61_000L)
        assertFalse(SurveyGimbalSettlePolicy.hasTimedOut(80_999L, startedAt))
        assertTrue(SurveyGimbalSettlePolicy.hasTimedOut(81_000L, startedAt))
        assertEquals(startedAt, StoppedCapturePosePolicy.verificationStartedAt(false, startedAt, 81_000L))
    }

    @Test fun stalePositionAndWrongAltitudeDoNotCountAsArrival() {
        assertFalse(StoppedCapturePosePolicy.arrived(aircraft.copy(
            aircraftLocationUpdatedAtNanos = nowNanos - 2_000_000_000L,
        ), target, point, nowNanos))
        assertFalse(StoppedCapturePosePolicy.arrived(aircraft.copy(
            relativeAltitudeUpdatedAtNanos = nowNanos - 2_000_000_000L,
        ), target, point, nowNanos))
        assertFalse(StoppedCapturePosePolicy.arrived(aircraft.copy(
            relativeAltitudeMeters = 7.1,
        ), target, point, nowNanos))
    }

    @Test fun captureStillRequiresLowSpeedFreshPoseAndStableDwell() {
        assertFalse(StoppedCapturePosePolicy.aligned(aircraft, target, point, 4.88, nowNanos))
        assertFalse(StoppedCapturePosePolicy.aligned(aircraft.copy(
            gimbalPitchDegrees = -45.0,
        ), target, point, 0.0, nowNanos))
        assertTrue(StoppedCapturePosePolicy.aligned(aircraft, target, point, 0.0, nowNanos))
        val stableSince = StoppedCapturePosePolicy.updatedStableSince(true, 0L, 61_000L)
        assertFalse(StoppedCapturePosePolicy.stable(stableSince, 61_799L))
        assertTrue(StoppedCapturePosePolicy.stable(stableSince, 61_800L))
        assertEquals(0L, StoppedCapturePosePolicy.updatedStableSince(false, stableSince, 61_800L))
    }
}
