package edu.playground.djivln.survey

import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyDjiBreakpointEstimatorTest {
    @Test fun projectsAircraftPositionOntoCurrentWaypointSegment() {
        val original = SurveyRegressionMissionFactory.create(GeoPoint(31.0, 121.0), false)
        val start = original.waypoints[0].point
        val end = original.waypoints[1].point
        val mission = original.copy(waypoints = original.waypoints)
        val midpointLatitude = (start.latitude + end.latitude) / 2.0
        val midpointLongitude = (start.longitude + end.longitude) / 2.0

        val refined = SurveyDjiBreakpointEstimator.refine(
            mission,
            WaylineBreakpoint(waylineId = 0, waypointId = 0, segmentProgress = 0.0),
            midpointLatitude,
            midpointLongitude,
        )

        assertEquals(0.5, refined.segmentProgress, 0.01)
    }

    @Test fun geometricFallbackNeverMovesAReportedBreakpointBackwards() {
        val mission = SurveyRegressionMissionFactory.create(GeoPoint(31.0, 121.0), false)
        val start = mission.waypoints[0].point
        val end = mission.waypoints[1].point
        val earlyLatitude = start.latitude + (end.latitude - start.latitude) * 0.2
        val earlyLongitude = start.longitude + (end.longitude - start.longitude) * 0.2

        val refined = SurveyDjiBreakpointEstimator.refine(
            mission,
            WaylineBreakpoint(waylineId = 0, waypointId = 0, segmentProgress = 0.8),
            earlyLatitude,
            earlyLongitude,
        )

        assertEquals(0.8, refined.segmentProgress, 0.0)
    }
}
