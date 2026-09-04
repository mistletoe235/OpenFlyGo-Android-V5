package edu.playground.djivln.survey

import edu.playground.djivln.adapter.dji.SurveyWpmzConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SurveyMissionExecutionOverridesTest {
    private val mission = SurveyMission(
        name = "active-recapture",
        cameraProfile = CameraProfile.GENERIC_4_BY_3,
        constraints = SurveyConstraints(
            speedMetersPerSecond = 2.8,
            takeoffSpeedMetersPerSecond = 3.0,
        ),
        roi = listOf(
            GeoPoint(31.0, 121.0, 40.0),
            GeoPoint(31.0, 121.001, 40.0),
            GeoPoint(31.001, 121.001, 40.0),
        ),
        waypoints = listOf(
            SurveyWaypoint(
                point = GeoPoint(31.0, 121.0, 40.0),
                headingDegrees = 0.0,
                gimbalPitchDegrees = -45.0,
                kind = SurveyWaypointKind.CAPTURE_POINT,
                captureAction = CaptureAction.CAPTURE_ON_REACH,
                passIndex = 0,
                captureView = SurveyCaptureView.LOCAL_OBLIQUE,
            ),
        ),
        estimatedPathMeters = 280.0,
        estimatedPhotoCount = 1,
        estimatedFlightSeconds = 100.0,
        activeMapping = ActiveMappingMetadata(
            selectionMethod = "test",
            groundTruthUsed = false,
            gsUsedForSelection = false,
            ordinaryGpsUsed = true,
            sourceCaptureCount = 1,
            surveyCaptureCount = 1,
            bridgeCaptureCount = 0,
            sourceEstimatedRouteDistanceMeters = 280.0,
        ),
    )

    @Test
    fun `active recapture speed override preserves route and mapping`() {
        val updated = SurveyMissionExecutionOverrides.activeRecaptureRouteSpeed(mission, 4.0)

        assertEquals(4.0, updated.constraints.speedMetersPerSecond, 0.0)
        assertEquals(4.0, updated.constraints.obliqueSpeedMetersPerSecond, 0.0)
        assertEquals(3.0, updated.constraints.takeoffSpeedMetersPerSecond, 0.0)
        assertEquals(70.0, updated.estimatedFlightSeconds, 0.0)
        assertSame(mission.waypoints, updated.waypoints)
        assertSame(mission.activeMapping, updated.activeMapping)
    }

    @Test
    fun `unchanged speeds reuse mission`() {
        assertSame(mission, SurveyMissionExecutionOverrides.activeRecaptureRouteSpeed(mission, 2.8))
    }

    @Test
    fun `active recapture accepts separate nadir and oblique speeds`() {
        val updated = SurveyMissionExecutionOverrides.activeRecaptureRouteSpeed(mission, 2.8, 5.0)

        assertEquals(2.8, updated.constraints.speedMetersPerSecond, 0.0)
        assertEquals(5.0, updated.constraints.obliqueSpeedMetersPerSecond, 0.0)
        assertEquals(56.0, updated.estimatedFlightSeconds, 0.0)
    }

    @Test
    fun `route speed override reaches WPMZ without changing transition speed`() {
        val updated = SurveyMissionExecutionOverrides.activeRecaptureRouteSpeed(mission, 4.0)
        val converted = SurveyWpmzConverter.convert(updated)

        assertEquals(4.0, converted.wayline.autoFlightSpeed, 0.0)
        converted.wayline.waypoints.forEach { waypoint ->
            assertEquals(4.0, waypoint.speed, 0.0)
        }
        assertEquals(3.0, converted.config.globalTransitionalSpeed, 0.0)
    }
}
