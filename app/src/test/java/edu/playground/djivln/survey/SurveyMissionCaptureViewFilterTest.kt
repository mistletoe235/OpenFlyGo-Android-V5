package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyMissionCaptureViewFilterTest {
    @Test
    fun `active recapture mission cannot be rewritten by five direction filter`() {
        val mission = SurveyRegressionMissionFactory.create(
            GeoPoint(31.2304, 121.4737),
            true,
        ).copy(
            activeMapping = ActiveMappingMetadata(
                selectionMethod = "test",
                groundTruthUsed = false,
                gsUsedForSelection = false,
                ordinaryGpsUsed = true,
                sourceCaptureCount = 1,
                surveyCaptureCount = 1,
                bridgeCaptureCount = 0,
                sourceEstimatedRouteDistanceMeters = 0.0,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SurveyMissionCaptureViewFilter.select(mission, setOf(SurveyCaptureView.NADIR))
        }
    }

    @Test
    fun `selection preserves terrain adjusted waypoint heights`() {
        val roi = listOf(
            GeoPoint(31.0, 121.0), GeoPoint(31.0, 121.0008),
            GeoPoint(31.0005, 121.0008), GeoPoint(31.0005, 121.0),
        )
        val base = SurveyPlanner.plan(
            name = "terrain-five",
            roi = roi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 40.0,
                collectionMode = SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
            ),
        )
        val terrain = SurveyTerrainPlan(
            "test", "sha", 4326, 40.0, 5.0, 5.0,
            5.0, 10.0, 40.0, 45.0,
        )
        val adjusted = base.copy(
            waypoints = base.waypoints.mapIndexed { index, waypoint ->
                waypoint.copy(point = waypoint.point.copy(altitudeMeters = 40.0 + index * 0.1))
            },
            terrainPlan = terrain,
        )
        val selectedViews = setOf(
            SurveyCaptureView.FORWARD_OBLIQUE,
            SurveyCaptureView.RIGHT_OBLIQUE,
        )

        val selected = SurveyMissionCaptureViewFilter.select(adjusted, selectedViews)

        assertNotEquals(adjusted.id, selected.id)
        assertEquals(selectedViews, selected.constraints.enabledCaptureViews)
        assertEquals(selectedViews, selected.waypoints.map { it.captureView }.toSet())
        assertEquals(terrain, selected.terrainPlan)
        assertEquals(
            selected.surveyPasses().indices.toList(),
            selected.surveyPasses().map { it.start.passIndex },
        )
        assertEquals(
            adjusted.waypoints.map { it.point }.filterIndexed { index, _ ->
                adjusted.waypoints[index].captureView in selectedViews
            },
            selected.waypoints.map { it.point },
        )
        assertTrue(SurveyPlanner.groundCoverage(selected).areaSquareMeters > 0.0)
    }
}
