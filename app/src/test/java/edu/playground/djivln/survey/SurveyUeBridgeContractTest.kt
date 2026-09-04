package edu.playground.djivln.survey

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyUeBridgeContractTest {
    @Test
    fun `telemetry contract fixes all coordinate conventions`() {
        val root = JSONObject(SurveyUeBridgeContract.encodeTelemetry(
            SurveyUeTelemetry(1L, 31.0, 121.0, 60.0, 90.0, -90.0,
                true, true, SurveyExecutionState.RUNNING, 3),
        ))

        assertEquals(SurveyUeBridgeContract.SCHEMA, root.getString("schema"))
        val contract = root.getJSONObject("coordinate_contract")
        assertEquals("WGS84", contract.getString("geodetic"))
        assertEquals("ENU", contract.getString("local_world"))
        assertEquals("FRU", contract.getString("vehicle_body"))
        assertFalse(root.has("command"))
        assertEquals(3, root.getJSONObject("execution").getInt("waypoint_index"))
    }

    @Test
    fun `target contract exposes the active route leg`() {
        val waypoint = SurveyWaypoint(
            point = GeoPoint(31.1, 121.2, 45.0),
            headingDegrees = 90.0,
            gimbalPitchDegrees = -45.0,
            kind = SurveyWaypointKind.PASS_START,
            captureAction = CaptureAction.START_DISTANCE_INTERVAL,
            captureIntervalMeters = 12.0,
            passIndex = 2,
            captureView = SurveyCaptureView.FORWARD_OBLIQUE,
        )
        val root = JSONObject(SurveyUeBridgeContract.encodeTarget(
            SurveyUeTarget(10L, "mission", SurveyExecutionState.RUNNING,
                SurveyExecutionPhase.SURVEY, 4, 3, waypoint),
        ))

        assertEquals("target", root.getString("type"))
        assertEquals("SURVEY", root.getJSONObject("execution").getString("phase"))
        assertEquals("FORWARD_OBLIQUE", root.getJSONObject("target").getString("capture_view"))
    }

    @Test
    fun `capture contract binds image to pose sequence`() {
        val root = JSONObject(SurveyUeBridgeContract.encodeCapture(
            SurveyUeCapture(
                20L, "mission", "distance interval", 12L, 345L,
                "jpeg", 1440, 1080, 99L, "Download/DJI-VLN/survey/mission/a.jpg",
                31.0, 121.0, 50.0, 180.0, -90.0, 5, 4,
            ),
        ))

        assertEquals("capture", root.getString("type"))
        assertEquals(345L, root.getLong("pose_sequence"))
        assertEquals(1440, root.getJSONObject("frame").getInt("width"))
        assertTrue(root.getString("saved_path").endsWith("a.jpg"))
    }

    @Test
    fun `authenticated HIL peer resolves blank and configured UE endpoints`() {
        assertEquals(
            "http://192.168.43.2:30010",
            SurveyUeBridgeClient.resolvedEndpoint("", "192.168.43.2"),
        )
        assertEquals(
            "http://192.168.43.2:31000/base",
            SurveyUeBridgeClient.resolvedEndpoint("http://10.0.0.5:31000/base", "192.168.43.2"),
        )
        assertEquals(
            "https://[fe80::1]:31000/base",
            SurveyUeBridgeClient.resolvedEndpoint("https://10.0.0.5:31000/base", "fe80::1"),
        )
    }
}
