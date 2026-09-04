package edu.playground.djivln.survey

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyDjiRemainingEstimatorTest {
    private val mission = SurveyRegressionMissionFactory.create(
        center = GeoPoint(31.025, 121.433, 0.0),
        fiveDirection = false,
    )

    @Test fun captureOnReachAddsCameraBudgetAtFinalWaypoint() {
        val waypoint = mission.waypoints.first().copy(
            kind = SurveyWaypointKind.CAPTURE_POINT,
            captureAction = CaptureAction.CAPTURE_ON_REACH,
            captureIntervalMeters = null,
        )
        val pointMission = mission.copy(waypoints = listOf(waypoint))
        assertEquals(SurveyEtaPolicy.CAPTURE_ON_REACH_SECONDS,
            SurveyDjiRemainingEstimator.estimate(pointMission, 0, waypoint.point).totalSeconds, 0.0)
    }

    @Test fun remainingTimeFallsAsWaypointProgressAdvances() {
        val start = SurveyDjiRemainingEstimator.estimate(
            mission = mission,
            waypointIndex = 0,
            currentPosition = mission.waypoints.first().point,
        )
        val middleIndex = mission.waypoints.size / 2
        val middle = SurveyDjiRemainingEstimator.estimate(
            mission = mission,
            waypointIndex = middleIndex,
            currentPosition = mission.waypoints[middleIndex].point,
        )

        assertTrue(start.totalSeconds > middle.totalSeconds)
        assertTrue(start.currentSectionSeconds > 0.0)
        assertTrue(middle.totalSeconds >= middle.currentSectionSeconds)
    }

    @Test fun liveSpeedChangesCurrentLegWithoutScalingAllFutureLegs() {
        val targetIndex = 1
        val a = mission.waypoints.first().point
        val b = mission.waypoints[1].point
        val position = GeoPoint((a.latitude + b.latitude) / 2, (a.longitude + b.longitude) / 2, a.altitudeMeters)
        val slow = SurveyDjiRemainingEstimator.estimate(
            mission = mission,
            waypointIndex = targetIndex,
            currentPosition = position,
            currentHorizontalSpeedMetersPerSecond = 0.8,
        )
        val fast = SurveyDjiRemainingEstimator.estimate(
            mission = mission,
            waypointIndex = targetIndex,
            currentPosition = position,
            currentHorizontalSpeedMetersPerSecond = 3.0,
        )

        assertTrue(slow.currentSectionSeconds > fast.currentSectionSeconds)
        assertTrue(slow.totalSeconds > fast.totalSeconds)
    }

    @Test fun currentPositionInsideLegReducesEstimate() {
        val targetIndex = 1
        val start = mission.waypoints.first().point
        val target = mission.waypoints[targetIndex].point
        val halfway = GeoPoint(
            latitude = (start.latitude + target.latitude) / 2.0,
            longitude = (start.longitude + target.longitude) / 2.0,
            altitudeMeters = (start.altitudeMeters + target.altitudeMeters) / 2.0,
        )
        val fromStart = SurveyDjiRemainingEstimator.estimate(mission, targetIndex, start)
        val fromHalfway = SurveyDjiRemainingEstimator.estimate(mission, targetIndex, halfway)

        assertTrue(fromStart.totalSeconds > fromHalfway.totalSeconds)
    }

    private fun point(north: Double, east: Double = 0.0, height: Double = 40.0) = GeoPoint(
        31.025 + north / 111_132.0,
        121.433 + east / (111_320.0 * kotlin.math.cos(Math.toRadians(31.025))), height)

    private fun longRoute(): SurveyMission {
        val points = listOf(point(0.0), point(900.0), point(900.0, 20.0), point(0.0, 20.0))
        return mission.copy(
            constraints = mission.constraints.copy(speedMetersPerSecond = 8.0, obliqueSpeedMetersPerSecond = 8.0,
                takeoffSpeedMetersPerSecond = 2.0, descentSpeedMetersPerSecond = 1.0),
            waypoints = points.mapIndexed { index, p -> SurveyWaypoint(p,
                if (index < 2) 0.0 else 180.0, -90.0,
                if (index % 2 == 0) SurveyWaypointKind.PASS_START else SurveyWaypointKind.PASS_END,
                if (index % 2 == 0) CaptureAction.START_DISTANCE_INTERVAL else CaptureAction.STOP_DISTANCE_INTERVAL,
                captureIntervalMeters = 10.0, passIndex = index / 2) },
        )
    }

    @Test fun staleStartIndexCountsOnlyTheUnflownPartOf900MeterStrip() {
        val route = longRoute()
        val samples = listOf(0.0, 100.0, 450.0, 890.0).map {
            SurveyDjiRemainingEstimator.estimate(route, 0, point(it)).totalSeconds
        }
        assertTrue(samples.zipWithNext().all { (a, b) -> a > b })
        assertEquals(890.0 / 8, samples.first() - samples.last(), 0.02)
    }

    @Test fun reachedEndpointAndIndexSwitchDoNotSubtractAnEntireStrip() {
        val route = longRoute()
        val before = SurveyDjiRemainingEstimator.estimate(route, 0, point(899.9)).totalSeconds
        val reachedOldIndex = SurveyDjiRemainingEstimator.estimate(route, 0, point(900.0)).totalSeconds
        val reachedNewIndex = SurveyDjiRemainingEstimator.estimate(route, 1, point(900.0)).totalSeconds
        assertTrue(kotlin.math.abs(before - reachedNewIndex) < 3.0)
        assertEquals(reachedOldIndex, reachedNewIndex, 0.001)
    }

    @Test fun targetIndexAndSegmentIndexReportsAgreeInsideSameLeg() {
        val route = longRoute()
        assertEquals(SurveyDjiRemainingEstimator.estimate(route, 0, point(450.0)),
            SurveyDjiRemainingEstimator.estimate(route, 1, point(450.0)))
    }

    @Test fun slowdownAndStoppedSpeedNearEndpointDoNotInflateEta() {
        val route = longRoute()
        val estimates = listOf(8.0, 0.5, 0.1, 0.0).map {
            SurveyDjiRemainingEstimator.estimate(route, 0, point(899.0), currentHorizontalSpeedMetersPerSecond = it)
        }
        assertTrue(estimates.all { it == estimates.first() })
    }

    @Test fun cursorAdvancesLocallyDespiteStaleCallbacksWithoutJumpingToParallelStrip() {
        val route = longRoute()
        val one = SurveyDjiRemainingEstimator.segmentIndex(route, 0, point(900.0))
        assertEquals(1, one)
        val two = SurveyDjiRemainingEstimator.segmentIndex(route, 0, point(900.0, 20.0), one)
        assertEquals(2, two)
        val estimate = SurveyDjiRemainingEstimator.estimate(route, 0, point(450.0, 20.0), minimumSegmentIndex = two)
        assertEquals(450.0 / 8, estimate.totalSeconds, 0.02)
    }

    @Test fun initialApproachAddsSafeVerticalAndHorizontalTravel() {
        val route = longRoute()
        val plan = SurveyDjiRemainingEstimator.planned(route)
        val approach = SurveyDjiRemainingEstimator.estimate(route, 0, point(-200.0, height = 0.0), isApproaching = true)
        assertEquals(120.0, approach.totalSeconds - plan.totalSeconds, 0.02)
    }

    @Test fun recoveryStartsAtBreakpointRatherThanRecountingWholeStrip() {
        val route = longRoute()
        val entry = point(450.0)
        val remaining = SurveyDjiRemainingEstimator.estimate(route, 0, entry)
        val recovery = SurveyDjiRemainingEstimator.estimate(route, 0, point(-200.0), isApproaching = true, approachPoint = entry)
        assertEquals(325.0, recovery.totalSeconds - remaining.totalSeconds, 0.02)
    }

    @Test fun verticalLegUsesDirectionalSpeedAndCompletionHasNoRemainingDistance() {
        val base = longRoute()
        fun vertical(from: Double, to: Double) = base.copy(waypoints = listOf(
            base.waypoints[0].copy(point = point(0.0, height = from)),
            base.waypoints[1].copy(point = point(0.0, height = to)),
        ))
        assertEquals(10.0, SurveyDjiRemainingEstimator.estimate(vertical(0.0, 40.0), 0, point(0.0, height = 20.0)).totalSeconds, 0.01)
        assertEquals(20.0, SurveyDjiRemainingEstimator.estimate(vertical(40.0, 0.0), 0, point(0.0, height = 20.0)).totalSeconds, 0.01)
        assertEquals(0.0, SurveyDjiRemainingEstimator.estimate(base, 3, base.waypoints.last().point).totalSeconds, 0.01)
    }

    @Test fun stationaryPauseEstimateDoesNotCountWallClockTime() {
        val route = longRoute()
        val first = SurveyDjiRemainingEstimator.estimate(route, 0, point(450.0))
        val second = SurveyDjiRemainingEstimator.estimate(route, 0, point(450.0))
        assertEquals(first, second)
    }

    @Test fun endpointGpsToleranceDoesNotTrapTheCursorOnAnOldStrip() {
        val route = longRoute()
        assertEquals(1, SurveyDjiRemainingEstimator.segmentIndex(route, 0, point(899.0)))
    }

    @Test fun proximityDoesNotSkipAnEntireShortTransitOrCoincidentRotation() {
        val base = longRoute()
        val short = base.copy(waypoints = base.waypoints.mapIndexed { index, waypoint ->
            if (index >= 2) waypoint.copy(point = point(if (index == 2) 900.0 else 0.0, 3.0)) else waypoint
        })
        assertEquals(1, SurveyDjiRemainingEstimator.segmentIndex(short, 0, point(900.0)))
        val rotation = base.copy(waypoints = listOf(base.waypoints[0], base.waypoints[1].copy(
            point = base.waypoints[0].point, headingDegrees = 180.0)))
        assertEquals(0, SurveyDjiRemainingEstimator.segmentIndex(rotation, 0, rotation.waypoints[0].point))
        assertTrue(SurveyDjiRemainingEstimator.estimate(rotation, 0, rotation.waypoints[0].point).totalSeconds >= 6.0)
    }
}
