package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyRegressionMissionFactoryTest {
    private val center = GeoPoint(31.2304, 121.4737)

    @Test
    fun `ortho regression has multiple passes and photos`() {
        val mission = SurveyRegressionMissionFactory.create(center, false)

        assertEquals(SurveyCollectionMode.ORTHO, mission.constraints.collectionMode)
        assertTrue(mission.waypoints.size >= 4)
        assertTrue(mission.estimatedPhotoCount > 2)
        assertTrue(mission.estimatedPathMeters > 50.0)
        assertTrue(SurveyPlanner.captureFeasibility(
            mission.cameraProfile, mission.constraints, oblique = false,
        ).feasible)
    }

    @Test
    fun `five direction regression covers every capture view`() {
        val mission = SurveyRegressionMissionFactory.create(center, true)

        assertEquals(SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
            mission.constraints.collectionMode)
        assertEquals(STANDARD_SURVEY_CAPTURE_VIEWS,
            mission.waypoints.map { it.captureView }.toSet())
        assertTrue(mission.estimatedPhotoCount >
            SurveyRegressionMissionFactory.create(center, false).estimatedPhotoCount)
        assertTrue(SurveyPlanner.captureFeasibility(
            mission.cameraProfile, mission.constraints, oblique = false,
        ).feasible)
        assertTrue(SurveyPlanner.captureFeasibility(
            mission.cameraProfile, mission.constraints, oblique = true,
        ).feasible)
    }
}
