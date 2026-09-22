package edu.playground.djivln.survey

import edu.playground.djivln.adapter.dji.DjiWaylinePartition
import edu.playground.djivln.adapter.dji.DjiWpmzContractValidator
import edu.playground.djivln.adapter.dji.SurveyWpmzConverter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoBuildingsBaseline200MissionTest {
    @Test
    fun `On-the-fly and SwiftMap review missions preserve the 18 target 200 photo contract`() {
        val methods = listOf(
            "onthefly",
            "swiftmap",
        )
        methods.forEach { method ->
            val mission = SurveyMissionJson.decode(
                File(fixtureDirectory(), "$method/openfly-survey-mission-schema13.json").readText(),
            )
            val report = ActiveRecaptureMissionValidator.validate(mission)
            val schedule = SurveyCaptureSchedule.build(mission)
            val metadata = requireNotNull(mission.activeMapping)

            assertEquals(200, report.captureCount)
            assertEquals(200, schedule.size)
            assertEquals(18, metadata.regions.size)
            assertEquals(200, metadata.passes.count { it.captureRole == "SURVEY" })
            assertEquals(17, metadata.passes.count { it.captureRole == "NONE" })
            assertEquals(200, report.pointCaptureCount)
            assertTrue(mission.waypoints.all { it.point.altitudeMeters in 50.0..90.0 })
            assertTrue(schedule.all { it.gimbalPitchDegrees in -90.0..-45.0 })

            val segments = DjiWaylinePartition.segments(mission)
            assertTrue(segments.size > 1)
            assertTrue(segments.all {
                it.mission.waypoints.size <= DjiWaylinePartition.MAX_WAYPOINTS_PER_WAYLINE
            })
            segments.forEach { segment ->
                val converted = SurveyWpmzConverter.convert(
                    segment.mission,
                    waylineId = segment.waylineId,
                )
                assertTrue(DjiWpmzContractValidator.validate(converted).isEmpty())
            }
        }
    }

    private fun fixtureDirectory(): File {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(root, "handoff/active_recapture/two_buildings/baselines"),
            File(root.parentFile, "handoff/active_recapture/two_buildings/baselines"),
        ).first { it.isDirectory }
    }
}
