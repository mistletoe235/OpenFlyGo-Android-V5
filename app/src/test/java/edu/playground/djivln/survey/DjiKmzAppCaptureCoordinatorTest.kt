package edu.playground.djivln.survey

import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DjiKmzAppCaptureCoordinatorTest {
    private val start = GeoPoint(31.0, 121.0, 40.0)
    private fun point(eastMeters: Double) = GeoPoint(
        31.0,
        121.0 + eastMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(31.0))),
        40.0,
    )

    private fun mission(intervalMeters: Double = 10.0) = SurveyMission(
        name = "android-distance-capture",
        cameraProfile = CameraProfile.GENERIC_4_BY_3,
        constraints = SurveyConstraints(speedMetersPerSecond = 4.0),
        roi = listOf(start, point(30.0), GeoPoint(31.0001, 121.0001, 40.0)),
        waypoints = listOf(
            SurveyWaypoint(
                point = start,
                headingDegrees = 90.0,
                gimbalPitchDegrees = -90.0,
                kind = SurveyWaypointKind.PASS_START,
                captureAction = CaptureAction.START_DISTANCE_INTERVAL,
                captureIntervalMeters = intervalMeters,
                passIndex = 0,
                captureView = SurveyCaptureView.NADIR,
            ),
            SurveyWaypoint(
                point = point(30.0),
                headingDegrees = 90.0,
                gimbalPitchDegrees = -90.0,
                kind = SurveyWaypointKind.PASS_END,
                captureAction = CaptureAction.STOP_DISTANCE_INTERVAL,
                passIndex = 0,
                captureView = SurveyCaptureView.NADIR,
            ),
        ),
        estimatedPathMeters = 30.0,
        estimatedPhotoCount = 4,
        estimatedFlightSeconds = 10.0,
    )

    @Test
    fun `camera busy defers distance request instead of consuming it`() {
        val coordinator = DjiKmzAppCaptureCoordinator(proximityMeters = 2.0)
        coordinator.arm(mission())

        val first = coordinator.tick(start, 0, 1_000L, true, 4.0)
        assertNotNull(first)
        coordinator.onCaptureResult(point(1.0), 1_300L, true)

        assertNull(coordinator.tick(point(11.0), 0, 3_500L, false, 4.0))
        val deferred = coordinator.tick(point(12.0), 0, 3_750L, true, 4.0)
        assertNotNull(deferred)
        assertEquals("distance_interval", deferred?.reason)
    }

    @Test
    fun `progress distinguishes approach transit from active capture`() {
        val coordinator = DjiKmzAppCaptureCoordinator(proximityMeters = 2.0)
        coordinator.arm(mission())

        assertEquals(false, coordinator.progress(0)?.captureActive)
        val first = coordinator.tick(start, 0, 1_000L, true, 4.0)
        assertNotNull(first)
        coordinator.onCaptureResult(point(1.0), 1_300L, true)
        assertEquals(true, coordinator.progress(0)?.captureActive)
    }

    @Test
    fun `waypoint advance before reaching survey start never triggers a photo`() {
        val coordinator = DjiKmzAppCaptureCoordinator(proximityMeters = 2.0)
        coordinator.arm(mission())

        // The aircraft is still flying to the first survey point, but DJI has already reported
        // the next waypoint index. The live position, not that index, gates the first capture.
        assertNull(coordinator.tick(point(-40.0), 1, 1_000L, true, 4.0))
        assertNotNull(coordinator.tick(start, 1, 5_000L, true, 4.0))
    }

    @Test
    fun `waypoint progress can skip completed passes on breakpoint resume`() {
        val coordinator = DjiKmzAppCaptureCoordinator(proximityMeters = 2.0)
        coordinator.arm(mission())

        assertNull(coordinator.tick(point(35.0), 2, 5_000L, true, 4.0))
    }

    @Test
    fun `breakpoint inside capture pass restarts coverage from recovery position`() {
        val coordinator = DjiKmzAppCaptureCoordinator(proximityMeters = 2.0)
        val recoveryPoint = point(16.0)
        coordinator.armFromBreakpoint(
            mission(),
            WaylineBreakpoint(
                waylineId = 0,
                waypointId = 0,
                segmentProgress = 16.0 / 30.0,
                latitude = recoveryPoint.latitude,
                longitude = recoveryPoint.longitude,
                altitudeMeters = recoveryPoint.altitudeMeters,
            ),
        )

        // The trip back to the breakpoint is a recovery transit and must stay silent even if DJI
        // reports the resumed waypoint index throughout that trip.
        assertNull(coordinator.tick(point(-40.0), 0, 1_000L, true, 12.0))
        assertNull(coordinator.tick(point(0.0), 0, 2_000L, true, 12.0))
        assertNull(coordinator.tick(recoveryPoint, 0, 5_000L, true, 0.0))

        assertNull(coordinator.tick(point(17.0), 0, 6_500L, true, 4.0))
        val resumedCapture = coordinator.tick(point(27.0), 0, 9_000L, true, 4.0)
        assertNotNull(resumedCapture)
        assertEquals("distance_interval", resumedCapture?.reason)
    }

    @Test
    fun `breakpoint resume exposes pass pitch before recovery capture is armed`() {
        val coordinator = DjiKmzAppCaptureCoordinator(proximityMeters = 2.0)
        val recoveryPoint = point(16.0)
        coordinator.armFromBreakpoint(
            mission(),
            WaylineBreakpoint(
                waylineId = 0,
                waypointId = 0,
                segmentProgress = 16.0 / 30.0,
                latitude = recoveryPoint.latitude,
                longitude = recoveryPoint.longitude,
                altitudeMeters = recoveryPoint.altitudeMeters,
            ),
        )

        val target = coordinator.currentGimbalTarget(0)
        assertNotNull(target)
        assertEquals(0, target?.passIndex)
        assertEquals(SurveyCaptureView.NADIR, target?.captureView)
        assertEquals(-90.0, target?.pitchDegrees ?: 0.0, 0.0)
    }

    @Test
    fun `transit unit advances without requesting a photo`() {
        val capturePoint = point(20.0)
        val mission = SurveyMission(
            name = "transit",
            cameraProfile = CameraProfile.GENERIC_4_BY_3,
            constraints = SurveyConstraints(),
            roi = listOf(start, point(30.0), GeoPoint(31.0001, 121.0001, 40.0)),
            waypoints = listOf(
                SurveyWaypoint(
                    point = start,
                    headingDegrees = 0.0,
                    gimbalPitchDegrees = -45.0,
                    kind = SurveyWaypointKind.TRANSIT,
                    captureAction = CaptureAction.NONE,
                    passIndex = 0,
                ),
                SurveyWaypoint(
                    point = point(10.0),
                    headingDegrees = 20.0,
                    gimbalPitchDegrees = -50.0,
                    kind = SurveyWaypointKind.TRANSIT,
                    captureAction = CaptureAction.NONE,
                    passIndex = 0,
                ),
                SurveyWaypoint(
                    point = capturePoint,
                    headingDegrees = 30.0,
                    gimbalPitchDegrees = -55.0,
                    kind = SurveyWaypointKind.CAPTURE_POINT,
                    captureAction = CaptureAction.CAPTURE_ON_REACH,
                    passIndex = 1,
                    captureView = SurveyCaptureView.LOCAL_OBLIQUE,
                ),
            ),
            estimatedPathMeters = 20.0,
            estimatedPhotoCount = 1,
            estimatedFlightSeconds = 10.0,
        )
        val coordinator = DjiKmzAppCaptureCoordinator(proximityMeters = 2.0)
        coordinator.arm(mission)

        assertNull(coordinator.tick(point(10.0), 1, 1_000L, true, 2.0))
        assertNotNull(coordinator.tick(capturePoint, 2, 2_000L, true, 0.0))
    }
}
