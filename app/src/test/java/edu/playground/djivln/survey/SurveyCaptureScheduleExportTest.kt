package edu.playground.djivln.survey

import java.io.File
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyCaptureScheduleExportTest {
    @Test
    fun `export deterministic AirSim scale five route mission`() {
        val center = GeoPoint(31.0252, 121.4394)
        val halfNorth = 80.0
        val halfEast = 150.0
        val latitudeDelta = halfNorth / 111_132.0
        val longitudeDelta = halfEast / (111_320.0 * cos(Math.toRadians(center.latitude)))
        val roi = listOf(
            GeoPoint(center.latitude + latitudeDelta, center.longitude - longitudeDelta),
            GeoPoint(center.latitude + latitudeDelta, center.longitude + longitudeDelta),
            GeoPoint(center.latitude - latitudeDelta, center.longitude + longitudeDelta),
            GeoPoint(center.latitude - latitudeDelta, center.longitude - longitudeDelta),
        )
        val mission = SurveyPlanner.plan(
            name = "AirSim env16 App-route proxy 300x160m",
            roi = roi,
            camera = CameraProfile.DJI_MINI_2,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 90.0,
                forwardOverlap = 0.45,
                sideOverlap = 0.70,
                speedMetersPerSecond = 5.0,
                routeHeadingDegrees = 0.0,
                collectionMode = SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
                obliqueGimbalPitchDegrees = -45.0,
                obliqueForwardOverlap = 0.45,
                obliqueSideOverlap = 0.60,
                boundaryMarginMeters = 0.0,
                startPointMode = SurveyStartPointMode.FIRST_ROUTE_START,
                completionAction = SurveyCompletionAction.HOVER,
            ),
            takeoffPoint = center,
        )
        val events = SurveyCaptureSchedule.build(mission)
        val output = File("build/outputs/survey/airsim_env16_app_route_proxy.json")
        output.parentFile.mkdirs()
        output.writeText(SurveyCaptureScheduleJson.encode(mission, events))

        assertEquals(STANDARD_SURVEY_CAPTURE_VIEWS, events.map { it.captureView }.toSet())
        assertEquals(mission.estimatedPhotoCount, events.size)
    }
}
