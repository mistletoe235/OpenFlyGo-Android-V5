package edu.playground.djivln.adapter.dji

import edu.playground.djivln.survey.SurveyMission
import edu.playground.djivln.survey.SurveyMissionJson
import edu.playground.djivln.survey.SurveyRegressionMissionFactory
import edu.playground.djivln.survey.GeoPoint
import edu.playground.djivln.survey.surveyPasses
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiWaylinePartitionTest {
    @Test
    fun `small mission remains one wayline`() {
        val mission = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = false,
        )

        val segments = DjiWaylinePartition.segments(mission)

        assertEquals(1, segments.size)
        assertEquals(0, segments.single().firstGlobalWaypointIndex)
        assertEquals(mission.waypoints.lastIndex, segments.single().lastGlobalWaypointIndex)
        assertEquals(mission.waypoints, segments.single().mission.waypoints)
    }

    @Test
    fun `V30 is split at complete survey unit boundaries`() {
        val mission = loadV30Mission()

        val segments = DjiWaylinePartition.segments(mission)

        assertEquals(listOf(190, 187, 189, 189, 188, 79), segments.map { it.mission.waypoints.size })
        assertEquals(1_022, segments.sumOf { it.mission.waypoints.size })
        assertTrue(segments.all { it.mission.waypoints.size <= DjiWaylinePartition.MAX_WAYPOINTS_PER_WAYLINE })
        assertEquals(mission.waypoints, segments.flatMap { it.mission.waypoints })

        val passBoundaries = mission.surveyPasses().flatMap { listOf(it.firstWaypointIndex, it.lastWaypointIndex) }.toSet()
        segments.forEach { segment ->
            assertTrue(segment.firstGlobalWaypointIndex in passBoundaries)
            assertTrue(segment.lastGlobalWaypointIndex in passBoundaries)
        }
    }

    @Test
    fun `local DJI waypoint indices map back to global mission indices`() {
        val mission = loadV30Mission()

        assertEquals(189, DjiWaylinePartition.globalWaypointIndex(mission, 0, 189))
        assertEquals(190, DjiWaylinePartition.globalWaypointIndex(mission, 1, 0))
        assertEquals(1_021, DjiWaylinePartition.globalWaypointIndex(mission, 5, 78))
    }

    @Test
    fun `remaining waylines continue after recovered segment`() {
        val mission = loadV30Mission()

        assertEquals(listOf(1, 2, 3, 4, 5), DjiWaylinePartition.remainingWaylineIds(mission, 0))
        assertEquals(listOf(4, 5), DjiWaylinePartition.remainingWaylineIds(mission, 3))
        assertTrue(DjiWaylinePartition.remainingWaylineIds(mission, 5).isEmpty())
    }

    @Test
    fun `segment only completes at its final local waypoint`() {
        val mission = loadV30Mission()

        assertTrue(!DjiWaylinePartition.reachedSegmentEnd(mission, 0, 3))
        assertTrue(!DjiWaylinePartition.reachedSegmentEnd(mission, 0, 188))
        assertTrue(DjiWaylinePartition.reachedSegmentEnd(mission, 0, 189))
        assertTrue(!DjiWaylinePartition.reachedSegmentEnd(mission, 1, 185))
        assertTrue(DjiWaylinePartition.reachedSegmentEnd(mission, 1, 186))
    }

    private fun loadV30Mission(): SurveyMission {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(root, "testdata/active-recapture/two-buildings/openfly-active-recapture-two-buildings-v30.json"),
            File(requireNotNull(root.parentFile), "testdata/active-recapture/two-buildings/openfly-active-recapture-two-buildings-v30.json"),
        ).first(File::isFile)
        return SurveyMissionJson.decode(fixture.readText())
    }
}
