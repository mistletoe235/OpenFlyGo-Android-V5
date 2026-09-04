package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SurveyMissionJsonTest {
    @Test
    fun `mission JSON round trip preserves executable fields`() {
        val mission = SurveyPlanner.plan(
            name = "round-trip",
            roi = listOf(
                GeoPoint(31.0000, 121.0000),
                GeoPoint(31.0000, 121.0008),
                GeoPoint(31.0005, 121.0008),
                GeoPoint(31.0005, 121.0000),
            ),
            constraints = SurveyConstraints(
                altitudeMetersAgl = 45.0,
                speedMetersPerSecond = 3.2,
                obliqueSpeedMetersPerSecond = 5.4,
                routeHeadingDegrees = 32.0,
                collectionMode = SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
                obliqueGimbalPitchDegrees = -48.0,
                altitudeMode = SurveyAltitudeMode.ABOVE_TARGET_SURFACE,
                targetSurfaceToTakeoffMeters = 12.5,
                safeTakeoffAltitudeMeters = 35.0,
                takeoffSpeedMetersPerSecond = 2.5,
                takeoffMode = SurveyTakeoffMode.AUTO_SIMULATOR_ONLY,
                startPointMode = SurveyStartPointMode.ROUTE_CORNER_4,
                completionAction = SurveyCompletionAction.HOVER,
                captureTriggerMode = SurveyCaptureTriggerMode.TIME,
                timedCaptureIntervalSeconds = 2.4,
                obliqueForwardOverlap = 0.77,
                obliqueSideOverlap = 0.66,
                obliqueHeadingMode = SurveyObliqueHeadingMode.FIXED_CAPTURE_DIRECTION,
                enabledCaptureViews = setOf(
                    SurveyCaptureView.NADIR,
                    SurveyCaptureView.LEFT_OBLIQUE,
                ),
            ),
        )

        val decoded = SurveyMissionJson.decode(SurveyMissionJson.encode(mission))

        assertEquals(mission.id, decoded.id)
        assertEquals("WGS84", decoded.coordinateFrame)
        assertEquals(mission.roi, decoded.roi)
        assertEquals(mission.waypoints, decoded.waypoints)
        assertEquals(mission.constraints, decoded.constraints)
        assertEquals(mission.estimatedPathMeters, decoded.estimatedPathMeters, 1.0e-9)
        assertTrue(decoded.waypoints.any { it.captureAction == CaptureAction.START_DISTANCE_INTERVAL })
    }

    @Test
    fun `schema 13 stores route and vertical speeds while older missions keep safe defaults`() {
        val mission = SurveyPlanner.plan(
            name = "margin-schema",
            roi = listOf(
                GeoPoint(31.0000, 121.0000),
                GeoPoint(31.0000, 121.0008),
                GeoPoint(31.0005, 121.0008),
                GeoPoint(31.0005, 121.0000),
            ),
            constraints = SurveyConstraints(boundaryMarginMeters = 8.0),
        )

        val encoded = JSONObject(SurveyMissionJson.encode(mission))
        assertEquals(13, encoded.getInt("schema_version"))
        assertEquals(8.0, encoded.getJSONObject("constraints").getDouble("boundary_margin_m"), 0.0)
        assertEquals(3.0, encoded.getJSONObject("constraints").getDouble("oblique_speed_mps"), 0.0)
        assertEquals(2.0, encoded.getJSONObject("constraints").getDouble("descent_speed_mps"), 0.0)

        encoded.put("schema_version", 11)
        encoded.getJSONObject("constraints").remove("oblique_speed_mps")
        val schema11 = SurveyMissionJson.decode(encoded.toString())
        assertEquals(schema11.constraints.speedMetersPerSecond, schema11.constraints.obliqueSpeedMetersPerSecond, 0.0)
        assertEquals(2.0, schema11.constraints.descentSpeedMetersPerSecond, 0.0)

        encoded.put("schema_version", 10)
        encoded.remove("active_mapping")
        val schema10 = SurveyMissionJson.decode(encoded.toString())
        assertEquals(
            SurveyObliqueHeadingMode.TRACK_ROUTE,
            schema10.constraints.obliqueHeadingMode,
        )

        encoded.put("schema_version", 8)
        encoded.getJSONObject("constraints").remove("oblique_heading_mode")
        val schema8 = SurveyMissionJson.decode(encoded.toString())
        assertEquals(SurveyObliqueHeadingMode.TRACK_ROUTE, schema8.constraints.obliqueHeadingMode)

        encoded.put("schema_version", 5)
        encoded.getJSONObject("constraints").remove("takeoff_mode")
        val schema5 = SurveyMissionJson.decode(encoded.toString())
        assertEquals(SurveyTakeoffMode.MANUAL, schema5.constraints.takeoffMode)

        encoded.put("schema_version", 3)
        encoded.getJSONObject("constraints").apply {
            remove("altitude_mode")
            remove("target_surface_to_takeoff_m")
            remove("safe_takeoff_altitude_m")
            remove("takeoff_speed_mps")
            remove("start_point_mode")
            remove("completion_action")
            remove("capture_trigger_mode")
            remove("timed_capture_interval_s")
            remove("oblique_forward_overlap")
            remove("oblique_side_overlap")
        }
        val schema3 = SurveyMissionJson.decode(encoded.toString())
        assertEquals(SurveyAltitudeMode.ABOVE_TARGET_SURFACE, schema3.constraints.altitudeMode)
        assertEquals(schema3.constraints.forwardOverlap, schema3.constraints.obliqueForwardOverlap, 0.0)

        encoded.put("schema_version", 2)
        encoded.getJSONObject("constraints").apply {
            remove("collection_mode")
            remove("oblique_gimbal_pitch_deg")
        }
        encoded.getJSONArray("waypoints").let { array ->
            repeat(array.length()) { array.getJSONObject(it).remove("capture_view") }
        }
        val schema2 = SurveyMissionJson.decode(encoded.toString())
        assertEquals(SurveyCollectionMode.ORTHO, schema2.constraints.collectionMode)
        assertEquals(8.0, schema2.constraints.boundaryMarginMeters, 0.0)

        encoded.put("schema_version", 1)
        encoded.getJSONObject("constraints").apply {
            remove("boundary_margin_m")
            put("safety_margin_m", 8.0)
        }
        val legacy = SurveyMissionJson.decode(encoded.toString())
        assertEquals(0.0, legacy.constraints.boundaryMarginMeters, 0.0)
    }
}
