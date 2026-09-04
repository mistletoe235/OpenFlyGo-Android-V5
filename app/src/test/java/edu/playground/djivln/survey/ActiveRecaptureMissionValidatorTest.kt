package edu.playground.djivln.survey

import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRecaptureMissionValidatorTest {
    @Test
    fun `compiled two buildings mission preserves capture contract`() {
        val root = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(root, "testdata/active-recapture/two-buildings/openfly-active-recapture-two-buildings-v1.json"),
            File(root.parentFile, "testdata/active-recapture/two-buildings/openfly-active-recapture-two-buildings-v1.json"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("compiled active recapture fixture not found from ${root.absolutePath}")
        val mission = SurveyMissionJson.decode(file.readText())

        val report = ActiveRecaptureMissionValidator.validate(mission)

        assertEquals(339, report.captureCount)
        assertEquals(33, report.pointCaptureCount)
        assertEquals(27, report.continuousPassCount)
        assertTrue(report.minimumAltitudeMeters >= 5.0)
        assertTrue(report.maximumAltitudeMeters <= 120.0)
        assertTrue(report.maximumAdjacentDistanceMeters <= 25.0 + 1.0e-6)
        assertTrue(report.maximumYawStepDegrees <= 45.0 + 1.0e-6)
        assertTrue(report.maximumGimbalPitchStepDegrees <= 15.0 + 1.0e-6)
        assertEquals(339, SurveyCaptureSchedule.build(mission).size)
        assertEquals(9, mission.activeMapping?.regions?.size)
        assertTrue(mission.activeMapping?.regions.orEmpty().all { it.targetWgs84 != null })
        val regions = mission.activeMapping?.regions.orEmpty().associateBy { it.regionId }
        val passes = mission.activeMapping?.passes.orEmpty().associateBy { it.passIndex }
        mission.surveyPasses().filter { it.isPointCapture }.forEach { pass ->
            val regionId = passes.getValue(pass.start.passIndex).regionId
            val target = requireNotNull(regions.getValue(regionId).targetWgs84)
            val north = (target.latitude - pass.start.point.latitude) * 111_132.0
            val east = (target.longitude - pass.start.point.longitude) * 111_320.0 *
                cos(Math.toRadians(pass.start.point.latitude))
            val targetBearing = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
            val headingError = kotlin.math.abs(
                (targetBearing - pass.start.headingDegrees + 540.0) % 360.0 - 180.0,
            )
            assertTrue("$regionId heading error=$headingError", headingError < 0.1)
        }
        val statistics = SurveyPlanner.statistics(mission)
        assertEquals(339, statistics.photoCountByView.values.sum())
        assertEquals(339, statistics.sorties.sumOf { it.estimatedPhotoCount })

        val replay = SurveyMissionReplay(mission, sampleSpacingMeters = 25.0)
        var replaySnapshot = replay.start()
        var replaySteps = 0
        while (replaySnapshot.state == SurveyReplayState.RUNNING && replaySteps < 10_000) {
            replaySnapshot = replay.advance()
            replaySteps++
        }
        assertEquals(SurveyReplayState.COMPLETED, replaySnapshot.state)
        assertTrue(replaySteps < 10_000)

        val now = 1_000_000L
        val start = mission.waypoints.first().point
        val gate = SurveySimulatorGate.evaluate(
            mission = mission,
            telemetry = SurveyExecutionTelemetry(
                connected = true,
                simulatorActive = true,
                simulatorFlying = true,
                virtualStickEnabled = true,
                sticksActive = false,
                latitude = start.latitude,
                longitude = start.longitude,
                altitudeMeters = start.altitudeMeters,
                updatedAtEpochMillis = now,
            ),
            nowEpochMillis = now,
            requireVirtualStick = true,
        )
        assertTrue("simulator gate blocks=${gate.blocks}", gate.allowed)
    }
}
