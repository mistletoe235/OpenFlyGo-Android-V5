package edu.playground.djivln.ui

import edu.playground.djivln.survey.SurveyCaptureView
import edu.playground.djivln.survey.CameraProfile
import edu.playground.djivln.survey.CaptureAction
import edu.playground.djivln.survey.GeoPoint
import edu.playground.djivln.survey.ActiveMappingPassMetadata
import edu.playground.djivln.survey.SurveyConstraints
import edu.playground.djivln.survey.SurveyMission
import edu.playground.djivln.survey.SurveyMissionJson
import edu.playground.djivln.survey.SurveyWaypoint
import edu.playground.djivln.survey.SurveyWaypointKind
import edu.playground.djivln.survey.surveyPasses
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SurveyRouteStyleTest {
    @Test
    fun `five capture views retain the V4 palette`() {
        val colors = SurveyCaptureView.values().associateWith(SurveyRouteStyle::color)

        assertEquals(0xFFFFB547.toInt(), colors.getValue(SurveyCaptureView.NADIR))
        assertEquals(0xFFFF6B6B.toInt(), colors.getValue(SurveyCaptureView.FORWARD_OBLIQUE))
        assertEquals(0xFFB77BFF.toInt(), colors.getValue(SurveyCaptureView.BACKWARD_OBLIQUE))
        assertEquals(0xFF55D69E.toInt(), colors.getValue(SurveyCaptureView.LEFT_OBLIQUE))
        assertEquals(0xFF55BDEB.toInt(), colors.getValue(SurveyCaptureView.RIGHT_OBLIQUE))
        assertEquals(SurveyCaptureView.values().size, colors.values.toSet().size)
        assertNotEquals(colors.getValue(SurveyCaptureView.NADIR), colors.getValue(SurveyCaptureView.FORWARD_OBLIQUE))
    }

    @Test
    fun `nadir route is emphasized like V4`() {
        assertEquals(4.5f, SurveyRouteStyle.width(SurveyCaptureView.NADIR, terrainColored = false))
        assertEquals(3.5f, SurveyRouteStyle.width(SurveyCaptureView.LEFT_OBLIQUE, terrainColored = false))
        assertEquals(6.5f, SurveyRouteStyle.width(SurveyCaptureView.NADIR, terrainColored = true))
        assertEquals(5f, SurveyRouteStyle.width(SurveyCaptureView.RIGHT_OBLIQUE, terrainColored = true))
    }

    @Test
    fun `only dedicated reconstruction passes use bridge styling`() {
        assertEquals(true, SurveyRouteStyle.isPureBridge(ActiveMappingPassMetadata(
            passIndex = 1,
            regionId = "1",
            role = "RECONSTRUCTION_BRIDGE",
            captureRole = "BRIDGE",
            source = "AUTO",
            requiredForReconstructionBridge = true,
        )))
        assertEquals(false, SurveyRouteStyle.isPureBridge(ActiveMappingPassMetadata(
            passIndex = 2,
            regionId = "R8_R9",
            role = "HIGH_RISE_SCAN",
            captureRole = "MIXED",
            source = "SURVEY_PLANNER",
            requiredForReconstructionBridge = true,
        )))
    }

    @Test
    fun `active recapture highlight stays inside current pass`() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOfNotNull(
            File(root, "testdata/active-recapture/two-buildings/openfly-active-recapture-two-buildings-v1.json"),
            root.parentFile?.let {
                File(it, "testdata/active-recapture/two-buildings/openfly-active-recapture-two-buildings-v1.json")
            },
        ).first(File::isFile)
        val mission = SurveyMissionJson.decode(fixture.readText())

        assertEquals(1, SurveyRouteStyle.activeRoutePoints(mission, 0).size)
        assertEquals(5, SurveyRouteStyle.activeRoutePoints(mission, 46).size)
    }

    @Test
    fun `ordinary mission highlight does not join separate passes with the same view`() {
        fun waypoint(latitude: Double, kind: SurveyWaypointKind, action: CaptureAction, pass: Int) =
            SurveyWaypoint(
                point = GeoPoint(latitude, 121.0, 60.0),
                headingDegrees = 0.0,
                gimbalPitchDegrees = -90.0,
                kind = kind,
                captureAction = action,
                captureIntervalMeters = if (action == CaptureAction.START_DISTANCE_INTERVAL) 10.0 else null,
                passIndex = pass,
                captureView = SurveyCaptureView.NADIR,
            )
        val mission = SurveyMission(
            name = "two passes",
            cameraProfile = CameraProfile.GENERIC_4_BY_3,
            constraints = SurveyConstraints(),
            roi = listOf(GeoPoint(30.0, 121.0), GeoPoint(30.0, 121.1), GeoPoint(30.1, 121.0)),
            waypoints = listOf(
                waypoint(30.00, SurveyWaypointKind.PASS_START, CaptureAction.START_DISTANCE_INTERVAL, 0),
                waypoint(30.01, SurveyWaypointKind.PASS_END, CaptureAction.STOP_DISTANCE_INTERVAL, 0),
                waypoint(30.02, SurveyWaypointKind.PASS_START, CaptureAction.START_DISTANCE_INTERVAL, 1),
                waypoint(30.03, SurveyWaypointKind.PASS_END, CaptureAction.STOP_DISTANCE_INTERVAL, 1),
            ),
            estimatedPathMeters = 1.0,
            estimatedPhotoCount = 4,
            estimatedFlightSeconds = 1.0,
        )

        assertEquals(
            listOf(30.02, 30.03),
            SurveyRouteStyle.activeRoutePoints(mission, 2).map { it.latitude },
        )
    }

    @Test
    fun `active recapture waypoint resolves its region for map focus`() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val directory = listOfNotNull(
            File(root, "testdata/active-recapture/two-buildings"),
            root.parentFile?.let { File(it, "testdata/active-recapture/two-buildings") },
        ).first { File(it, "openfly-active-recapture-two-buildings-v30-manifest.json").isFile }
        val manifest = org.json.JSONObject(
            File(directory, "openfly-active-recapture-two-buildings-v30-manifest.json").readText(),
        )
        val filename = manifest.getJSONArray("sorties").getJSONObject(0).getString("file")
        val mission = SurveyMissionJson.decode(File(directory, filename).readText())
        val firstPass = mission.surveyPasses().first()
        val expectedRegion = mission.activeMapping?.passes
            ?.first { it.passIndex == firstPass.start.passIndex }
            ?.regionId

        assertEquals(expectedRegion, SurveyRouteStyle.regionId(mission, firstPass.firstWaypointIndex))
    }
}
