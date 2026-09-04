package edu.playground.djivln.survey

import edu.playground.djivln.adapter.dji.DjiWpmzContractValidator
import edu.playground.djivln.adapter.dji.SurveyWpmzConverter
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V30ActiveRecaptureMissionTest {
    @Test
    fun `V30 sorties preserve all captures heights and high-rise views`() {
        val directory = fixtureDirectory()
        val manifest = JSONObject(
            File(directory, "openfly-active-recapture-two-buildings-v30-manifest.json").readText(),
        )
        val sortieEntries = manifest.getJSONArray("sorties")
        val missions = (0 until sortieEntries.length()).map { index ->
            val filename = sortieEntries.getJSONObject(index).getString("file")
            SurveyMissionJson.decode(File(directory, filename).readText())
        }

        val schedules = missions.map { mission ->
            val report = ActiveRecaptureMissionValidator.validate(mission)
            val schedule = SurveyCaptureSchedule.build(mission)
            val converted = SurveyWpmzConverter.convert(mission)
            assertEquals(mission.estimatedPhotoCount, report.captureCount)
            assertEquals(mission.estimatedPhotoCount, schedule.size)
            assertEquals(mission.waypoints.size, converted.wayline.waypoints.size)
            assertTrue(DjiWpmzContractValidator.validate(converted).isEmpty())
            assertTrue(mission.surveyPasses().filter { it.isTransitOnly }.all { transit ->
                schedule.none { it.passIndex == transit.start.passIndex }
            })
            schedule
        }
        val captures = schedules.flatten()
        assertEquals(636, captures.size)
        assertEquals(
            mapOf(50.0 to 372, 52.0 to 4, 70.0 to 260),
            captures.groupingBy { it.point.altitudeMeters }.eachCount(),
        )

        val highRiseViews = missions.flatMap { mission ->
            val highRisePassIndices = mission.activeMapping?.passes.orEmpty()
                .filter { it.role == "HIGH_RISE_FIVE_DIRECTION" }
                .map { it.passIndex }
                .toSet()
            mission.surveyPasses()
                .filter { it.start.passIndex in highRisePassIndices }
                .map { it.start.captureView }
        }.toSet()
        assertEquals(STANDARD_SURVEY_CAPTURE_VIEWS, highRiseViews)
    }

    @Test
    fun `continuous-first V30 preserves captures and defers scattered scans`() {
        val directory = fixtureDirectory()
        val mission = SurveyMissionJson.decode(
            File(
                directory,
                "openfly-active-recapture-two-buildings-v30-continuous-first.json",
            ).readText(),
        )

        val report = ActiveRecaptureMissionValidator.validate(mission)
        val schedule = SurveyCaptureSchedule.build(mission)
        val converted = SurveyWpmzConverter.convert(mission)
        val captureRoles = mission.activeMapping?.passes.orEmpty()
            .map { it.role }
            .filterNot { it == "TRANSIT" || it == "SAFE_TRANSIT" }

        assertEquals(636, report.captureCount)
        assertEquals(636, schedule.size)
        assertEquals(mapOf(50.0 to 372, 52.0 to 4, 70.0 to 260), schedule.groupingBy {
            it.point.altitudeMeters
        }.eachCount())
        assertEquals(mission.waypoints.size, converted.wayline.waypoints.size)
        assertTrue(DjiWpmzContractValidator.validate(converted).isEmpty())
        assertTrue(captureRoles.takeWhile { it != "SMALL_CROSS" }.all {
            it == "HIGH_RISE_FIVE_DIRECTION" || it == "LARGE_FIVE_DIRECTION"
        })
        assertTrue(captureRoles.dropWhile { it != "SMALL_CROSS" }.dropWhile {
            it == "SMALL_CROSS"
        }.all { it == "V26_RISK_SCAN" || it == "LOCAL_PRECISE_CAPTURE" })
    }

    private fun fixtureDirectory(): File {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(root, "testdata/active-recapture/two-buildings"),
            File(root.parentFile, "testdata/active-recapture/two-buildings"),
        ).first { File(it, "openfly-active-recapture-two-buildings-v30-manifest.json").isFile }
    }
}
