package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyDistanceCaptureControllerTest {
    private val origin = GeoPoint(31.2304, 121.4737, 60.0)
    private fun point(northMeters: Double) = origin.copy(
        latitude = origin.latitude + northMeters / 111_132.0,
    )
    private fun waypoint(action: CaptureAction, interval: Double? = null) = SurveyWaypoint(
        origin, 0.0, -90.0, SurveyWaypointKind.PASS_START, action, interval, 0,
    )

    @Test
    fun `point capture requests once without enabling interval mode`() {
        val controller = SurveyDistanceCaptureController()
        val pointCapture = SurveyWaypoint(
            origin, 120.0, -45.0, SurveyWaypointKind.CAPTURE_POINT,
            CaptureAction.CAPTURE_ON_REACH, null, 0, SurveyCaptureView.LOCAL_OBLIQUE,
        )

        assertTrue(controller.onWaypointReached(pointCapture, origin, 1_000L, true))
        assertFalse(controller.active)
        controller.onCaptureResult(origin, 1_280L, true)
        assertFalse(controller.onPosition(point(20.0), 5_000L, true, 4.0))
    }

    @Test
    fun `distance trigger leads exposure by speed times learned latency`() {
        val controller = SurveyDistanceCaptureController()
        assertTrue(controller.onWaypointReached(
            waypoint(CaptureAction.START_DISTANCE_INTERVAL, 10.0), origin, 1_000L, true,
        ))
        assertFalse(controller.onPosition(point(20.0), 1_200L, true, 5.0))
        controller.onCaptureResult(point(2.0), 1_280L, true)

        assertEquals(1.4, controller.compensationLeadMeters(5.0), 1.0e-9)
        assertFalse(controller.onPosition(point(10.5), 2_400L, true, 5.0))
        assertTrue(controller.onPosition(point(10.7), 2_400L, true, 5.0))
    }

    @Test
    fun `camera busy defers first pass photo without losing capture state`() {
        val controller = SurveyDistanceCaptureController()
        assertFalse(controller.onWaypointReached(
            waypoint(CaptureAction.START_DISTANCE_INTERVAL, 8.0), origin, 1_000L, false,
        ))
        assertTrue(controller.active)
        assertTrue(controller.onPosition(origin, 1_100L, true))
    }

    @Test
    fun `successful callback updates latency estimate and image anchor`() {
        val controller = SurveyDistanceCaptureController(initialCaptureLatencyMillis = 280L)
        controller.onWaypointReached(
            waypoint(CaptureAction.START_DISTANCE_INTERVAL, 10.0), origin, 1_000L, true,
        )

        controller.onCaptureResult(point(3.0), 1_600L, true)

        assertEquals(360L, controller.estimatedCaptureLatencyMillis)
        assertFalse(controller.onPosition(point(11.0), 2_300L, true, 5.0))
        assertTrue(controller.onPosition(point(11.2), 2_300L, true, 5.0))
    }

    @Test
    fun `failed photo is not treated as a captured image anchor`() {
        val controller = SurveyDistanceCaptureController()
        controller.onWaypointReached(
            waypoint(CaptureAction.START_DISTANCE_INTERVAL, 10.0), origin, 1_000L, true,
        )
        controller.onCaptureResult(point(0.5), 1_300L, false)

        assertFalse(controller.onPosition(point(5.0), 2_199L, true, 5.0))
        assertTrue(controller.onPosition(point(5.0), 2_200L, true, 5.0))
        controller.onCaptureResult(point(6.5), 2_480L, true)

        assertFalse(controller.onPosition(point(14.9), 3_500L, true, 5.0))
        assertTrue(controller.onPosition(point(15.2), 3_680L, true, 5.0))
    }

    @Test
    fun `pass end stays active until final request completes`() {
        val controller = SurveyDistanceCaptureController()
        controller.onWaypointReached(
            waypoint(CaptureAction.START_DISTANCE_INTERVAL, 10.0), origin, 1_000L, true,
        )
        controller.onCaptureResult(point(1.5), 1_280L, true)

        assertTrue(controller.onWaypointReached(
            waypoint(CaptureAction.STOP_DISTANCE_INTERVAL), point(6.0), 2_200L, true,
        ))
        assertTrue(controller.active)
        controller.onCaptureResult(point(7.5), 2_480L, false)
        assertFalse(controller.active)
        assertFalse(controller.onPosition(point(20.0), 5_000L, true, 5.0))
    }

    @Test
    fun `pass end waits for camera and request cooldown`() {
        val controller = SurveyDistanceCaptureController()
        controller.onWaypointReached(
            waypoint(CaptureAction.START_DISTANCE_INTERVAL, 10.0), origin, 1_000L, true,
        )
        controller.onCaptureResult(point(1.5), 1_280L, true)

        assertFalse(controller.onWaypointReached(
            waypoint(CaptureAction.STOP_DISTANCE_INTERVAL), point(6.0), 1_400L, false,
        ))
        assertTrue(controller.active)
        assertFalse(controller.onWaypointReached(
            waypoint(CaptureAction.STOP_DISTANCE_INTERVAL), point(6.0), 2_199L, true,
        ))
        assertTrue(controller.active)
        assertTrue(controller.onWaypointReached(
            waypoint(CaptureAction.STOP_DISTANCE_INTERVAL), point(6.0), 2_200L, true,
        ))
    }

    @Test
    fun `restored active pass safely requests a fresh overlap frame`() {
        val controller = SurveyDistanceCaptureController()
        controller.restoreActive(10.0)

        assertTrue(controller.active)
        assertTrue(controller.onPosition(origin, 5_000L, true))
    }

    @Test
    fun `time trigger compensates callback latency`() {
        val controller = SurveyDistanceCaptureController()
        controller.configure(SurveyCaptureTriggerMode.TIME, 3.0)
        assertTrue(controller.onWaypointReached(
            waypoint(CaptureAction.START_DISTANCE_INTERVAL, 10.0), origin, 1_000L, true,
        ))
        controller.onCaptureResult(origin, 1_280L, true)

        assertFalse(controller.onPosition(origin, 3_999L, true))
        assertTrue(controller.onPosition(origin, 4_000L, true))
    }

    @Test
    fun `time trigger final frame uses compensated half interval`() {
        val controller = SurveyDistanceCaptureController()
        controller.configure(SurveyCaptureTriggerMode.TIME, 4.0)
        controller.onWaypointReached(
            waypoint(CaptureAction.START_DISTANCE_INTERVAL, 10.0), origin, 1_000L, true,
        )
        controller.onCaptureResult(origin, 1_280L, true)

        assertTrue(controller.onWaypointReached(
            waypoint(CaptureAction.STOP_DISTANCE_INTERVAL), origin, 3_000L, true,
        ))
    }

    @Test
    fun `lead distance is capped to avoid triggering too early`() {
        val controller = SurveyDistanceCaptureController(initialCaptureLatencyMillis = 1_000L)
        controller.restoreActive(10.0)

        assertEquals(4.0, controller.compensationLeadMeters(10.0), 0.0)
    }
}
