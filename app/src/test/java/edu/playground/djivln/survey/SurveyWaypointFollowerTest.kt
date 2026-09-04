package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyWaypointFollowerTest {
    private val origin = GeoPoint(31.2304, 121.4737, 10.0)

    private fun waypoint(
        northMeters: Double,
        eastMeters: Double,
        upMeters: Double = 0.0,
        headingDegrees: Double = 0.0,
    ): SurveyWaypoint = SurveyWaypoint(
        point = GeoPoint(
            latitude = origin.latitude + northMeters / 111_132.0,
            longitude = origin.longitude + eastMeters /
                (111_320.0 * kotlin.math.cos(Math.toRadians(origin.latitude))),
            altitudeMeters = origin.altitudeMeters + upMeters,
        ),
        headingDegrees = headingDegrees,
        gimbalPitchDegrees = -90.0,
        kind = SurveyWaypointKind.PASS_START,
        captureAction = CaptureAction.NONE,
        passIndex = 0,
    )

    @Test
    fun `north target is body forward when aircraft faces north`() {
        val command = SurveyWaypointFollower.command(
            SurveyFollowerPose(origin.latitude, origin.longitude, 10.0, 0.0),
            waypoint(northMeters = 10.0, eastMeters = 0.0),
            2.0,
        )

        assertTrue(command.forwardMetersPerSecond > 1.9)
        assertEquals(0.0, command.rightMetersPerSecond, 0.02)
    }

    @Test
    fun `east target becomes forward when aircraft faces east`() {
        val command = SurveyWaypointFollower.command(
            SurveyFollowerPose(origin.latitude, origin.longitude, 10.0, 90.0),
            waypoint(northMeters = 0.0, eastMeters = 10.0, headingDegrees = 90.0),
            2.0,
        )

        assertTrue(command.forwardMetersPerSecond > 1.9)
        assertEquals(0.0, command.rightMetersPerSecond, 0.02)
        assertEquals(0.0, command.yawRateDegreesPerSecond, 0.01)
    }

    @Test
    fun `horizontal vertical and yaw commands are bounded`() {
        val command = SurveyWaypointFollower.command(
            SurveyFollowerPose(origin.latitude, origin.longitude, 10.0, 170.0),
            waypoint(100.0, 100.0, upMeters = 20.0, headingDegrees = 350.0),
            1.5,
        )

        assertEquals(1.5, kotlin.math.hypot(
            command.forwardMetersPerSecond,
            command.rightMetersPerSecond,
        ), 1.0e-6)
        assertEquals(0.5, command.upMetersPerSecond, 0.0)
        assertTrue(kotlin.math.abs(command.yawRateDegreesPerSecond) <= 30.0)
    }

    @Test
    fun `safe climb can use the configured vertical speed limit`() {
        val command = SurveyWaypointFollower.command(
            SurveyFollowerPose(origin.latitude, origin.longitude, 10.0, 0.0),
            waypoint(0.0, 0.0, upMeters = 20.0),
            maximumHorizontalSpeedMetersPerSecond = 3.0,
            maximumVerticalSpeedMetersPerSecond = 3.0,
        )

        assertEquals(3.0, command.upMetersPerSecond, 0.0)
    }

    @Test
    fun `inside tolerance emits an exact zero command`() {
        val command = SurveyWaypointFollower.command(
            SurveyFollowerPose(origin.latitude, origin.longitude, 10.0, 42.0),
            waypoint(0.5, 0.4, upMeters = 0.2, headingDegrees = 42.0),
            2.0,
        )

        assertTrue(command.reached)
        assertEquals(0.0, command.forwardMetersPerSecond, 0.0)
        assertEquals(0.0, command.rightMetersPerSecond, 0.0)
        assertEquals(0.0, command.upMetersPerSecond, 0.0)
        assertEquals(0.0, command.yawRateDegreesPerSecond, 0.0)
    }

    @Test
    fun `outside vertical tolerance is not reached`() {
        val command = SurveyWaypointFollower.command(
            SurveyFollowerPose(origin.latitude, origin.longitude, 9.0, 0.0),
            waypoint(0.0, 0.0),
            2.0,
        )
        assertFalse(command.reached)
        assertTrue(command.upMetersPerSecond > 0.0)
    }

    @Test
    fun `inside position tolerance still aligns heading before reaching waypoint`() {
        val command = SurveyWaypointFollower.command(
            SurveyFollowerPose(origin.latitude, origin.longitude, 10.0, 0.0),
            waypoint(0.0, 0.0, headingDegrees = 90.0),
            2.0,
        )

        assertFalse(command.reached)
        assertTrue(command.yawRateDegreesPerSecond > 0.0)
        assertEquals(0.0, command.forwardMetersPerSecond, 0.0)
        assertEquals(0.0, command.rightMetersPerSecond, 0.0)
    }

    @Test
    fun `transit waits for nose alignment before horizontal motion`() {
        val command = SurveyWaypointFollower.command(
            pose = SurveyFollowerPose(origin.latitude, origin.longitude, 10.0, 180.0),
            target = waypoint(20.0, 0.0, headingDegrees = 0.0),
            maximumHorizontalSpeedMetersPerSecond = 2.0,
            alignHeadingBeforeHorizontalMotion = true,
        )

        assertFalse(command.reached)
        assertEquals(0.0, command.forwardMetersPerSecond, 0.0)
        assertEquals(0.0, command.rightMetersPerSecond, 0.0)
        assertTrue(kotlin.math.abs(command.yawRateDegreesPerSecond) > 0.0)
    }

    @Test
    fun `survey motion may continue while correcting a small heading error`() {
        val command = SurveyWaypointFollower.command(
            pose = SurveyFollowerPose(origin.latitude, origin.longitude, 10.0, 10.0),
            target = waypoint(20.0, 0.0, headingDegrees = 0.0),
            maximumHorizontalSpeedMetersPerSecond = 2.0,
            alignHeadingBeforeHorizontalMotion = false,
        )

        assertTrue(kotlin.math.hypot(command.forwardMetersPerSecond, command.rightMetersPerSecond) > 0.0)
    }
}
