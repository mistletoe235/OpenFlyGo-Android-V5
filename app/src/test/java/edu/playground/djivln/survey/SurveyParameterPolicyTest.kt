package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SurveyParameterPolicyTest {
    @Test
    fun `percent inputs and aviation heading become planner constraints`() {
        val constraints = SurveyParameterPolicy.createConstraints(
            60.0, -10.0, 80.0, 70.0, 3.0, -45.0, 2.0, true,
        )

        assertEquals(0.8, constraints.forwardOverlap, 0.0)
        assertEquals(0.7, constraints.sideOverlap, 0.0)
        assertEquals(350.0, constraints.routeHeadingDegrees, 0.0)
        assertEquals(2.0, constraints.boundaryMarginMeters, 0.0)
        assertEquals(SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION, constraints.collectionMode)
        assertEquals(-90.0, constraints.gimbalPitchDegrees, 0.0)
        assertEquals(-45.0, constraints.obliqueGimbalPitchDegrees, 0.0)
    }

    @Test
    fun `unsafe overlap speed gimbal and margin are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SurveyParameterPolicy.createConstraints(60.0, 0.0, 95.0, 70.0, 3.0, -90.0, 0.0, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SurveyParameterPolicy.createConstraints(60.0, 0.0, 80.0, 70.0, 10.1, -90.0, 0.0, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SurveyParameterPolicy.createConstraints(60.0, 0.0, 80.0, 70.0, 3.0, -20.0, 0.0, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SurveyParameterPolicy.createConstraints(60.0, 0.0, 80.0, 70.0, 3.0, -90.0, 40.0, false)
        }
    }

    @Test
    fun `pilot mission options are retained by parameter policy`() {
        val constraints = SurveyParameterPolicy.createConstraints(
            altitudeMetersAgl = 55.0,
            routeHeadingDegrees = 20.0,
            forwardOverlapPercent = 80.0,
            sideOverlapPercent = 70.0,
            speedMetersPerSecond = 3.0,
            gimbalPitchDegrees = -45.0,
            boundaryMarginMeters = 5.0,
            obliqueFiveDirection = true,
            altitudeMode = SurveyAltitudeMode.RELATIVE_TO_TAKEOFF,
            startPointMode = SurveyStartPointMode.FIRST_ROUTE_START,
            completionAction = SurveyCompletionAction.HOVER,
            captureTriggerMode = SurveyCaptureTriggerMode.TIME,
            timedCaptureIntervalSeconds = 3.0,
        )

        assertEquals(SurveyAltitudeMode.RELATIVE_TO_TAKEOFF, constraints.altitudeMode)
        assertEquals(SurveyStartPointMode.FIRST_ROUTE_START, constraints.startPointMode)
        assertEquals(SurveyCompletionAction.HOVER, constraints.completionAction)
        assertEquals(SurveyCaptureTriggerMode.TIME, constraints.captureTriggerMode)
        assertEquals(3.0, constraints.timedCaptureIntervalSeconds, 0.0)
    }

    @Test
    fun `effective route altitude and DJI vertical velocity stay within execution limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            SurveyParameterPolicy.createConstraints(
                60.0, 0.0, 80.0, 70.0, 3.0, -90.0, 0.0, false,
                targetSurfaceToTakeoffMeters = 70.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SurveyParameterPolicy.createConstraints(
                60.0, 0.0, 80.0, 70.0, 3.0, -90.0, 0.0, false,
                takeoffSpeedMetersPerSecond = 6.1,
            )
        }
    }

    @Test
    fun `survey horizontal speed allows ten while vertical speed follows MSDK six meter limit`() {
        val constraints = SurveyParameterPolicy.createConstraints(
            60.0, 0.0, 80.0, 70.0, 10.0, -90.0, 0.0, false,
            takeoffSpeedMetersPerSecond = 6.0,
            descentSpeedMetersPerSecond = 5.0,
            captureTriggerMode = SurveyCaptureTriggerMode.TIME,
            obliqueSpeedMetersPerSecond = 7.0,
        )

        assertEquals(10.0, constraints.speedMetersPerSecond, 0.0)
        assertEquals(7.0, constraints.obliqueSpeedMetersPerSecond, 0.0)
        assertEquals(6.0, constraints.takeoffSpeedMetersPerSecond, 0.0)
        assertEquals(5.0, constraints.descentSpeedMetersPerSecond, 0.0)
    }

    @Test
    fun `timed capture accepts one second and rejects shorter intervals`() {
        val constraints = SurveyParameterPolicy.createConstraints(
            60.0, 0.0, 80.0, 70.0, 3.0, -90.0, 0.0, false,
            captureTriggerMode = SurveyCaptureTriggerMode.TIME,
            timedCaptureIntervalSeconds = 1.0,
        )

        assertEquals(1.0, constraints.timedCaptureIntervalSeconds, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            SurveyParameterPolicy.createConstraints(
                60.0, 0.0, 80.0, 70.0, 3.0, -90.0, 0.0, false,
                captureTriggerMode = SurveyCaptureTriggerMode.TIME,
                timedCaptureIntervalSeconds = 0.9,
            )
        }
    }
}
