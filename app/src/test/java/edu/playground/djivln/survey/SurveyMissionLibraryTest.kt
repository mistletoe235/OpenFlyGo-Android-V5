package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyMissionLibraryTest {
    private fun mission(name: String, heading: Double = 0.0) = SurveyPlanner.plan(
        name,
        listOf(
            GeoPoint(31.0, 121.0),
            GeoPoint(31.0, 121.001),
            GeoPoint(31.0006, 121.001),
            GeoPoint(31.0006, 121.0),
        ),
        constraints = SurveyConstraints(routeHeadingDegrees = heading),
    )

    @Test
    fun `same task name receives monotonic revisions and round trips`() {
        var versions = SurveyMissionLibrary.addVersion(emptyList(), mission("campus"), 10L)
        versions = SurveyMissionLibrary.addVersion(versions, mission("campus", 45.0), 20L)
        versions = SurveyMissionLibrary.addVersion(versions, mission("roof"), 30L)

        val decoded = SurveyMissionLibrary.decode(SurveyMissionLibrary.encode(versions))
        assertEquals(listOf("roof", "campus", "campus"), decoded.map { it.missionName })
        assertEquals(listOf(1, 2, 1), decoded.map { it.revision })
        assertEquals(45.0, decoded[1].mission().constraints.routeHeadingDegrees, 0.0)
    }

    @Test
    fun `library keeps only newest bounded history`() {
        var versions = emptyList<SurveyMissionVersion>()
        repeat(SurveyMissionLibrary.MAX_VERSIONS + 5) { index ->
            versions = SurveyMissionLibrary.addVersion(versions, mission("m"), index.toLong())
        }

        assertEquals(SurveyMissionLibrary.MAX_VERSIONS, versions.size)
        assertEquals(55, versions.first().revision)
        assertTrue(versions.last().revision > 1)
    }
}
