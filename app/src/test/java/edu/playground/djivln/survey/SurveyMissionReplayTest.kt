package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyMissionReplayTest {
    private fun mission() = SurveyPlanner.plan(
        name = "replay",
        roi = listOf(
            GeoPoint(31.0000, 121.0000),
            GeoPoint(31.0000, 121.0005),
            GeoPoint(31.0004, 121.0005),
            GeoPoint(31.0004, 121.0000),
        ),
        constraints = SurveyConstraints(altitudeMetersAgl = 40.0),
    )

    @Test
    fun `preview has explicit lifecycle and deterministic progress`() {
        val replay = SurveyMissionReplay(mission(), sampleSpacingMeters = 5.0)

        assertEquals(SurveyReplayState.IDLE, replay.snapshot().state)
        assertEquals(0.0, replay.snapshot().progress, 0.0)

        replay.start()
        repeat(3) { replay.advance() }
        val paused = replay.pause()
        assertEquals(SurveyReplayState.PAUSED, paused.state)
        val pausedIndex = paused.sampleIndex
        replay.advance()
        assertEquals(pausedIndex, replay.snapshot().sampleIndex)

        replay.resume()
        while (replay.snapshot().state != SurveyReplayState.COMPLETED) replay.advance()
        assertEquals(1.0, replay.snapshot().progress, 0.0)

        val reset = replay.stop()
        assertEquals(SurveyReplayState.IDLE, reset.state)
        assertEquals(0, reset.sampleIndex)
    }

    @Test
    fun `capture state is active on survey passes and inactive on some transits`() {
        val replay = SurveyMissionReplay(mission(), sampleSpacingMeters = 1.0)
        replay.start()
        var sawCapture = replay.snapshot().captureActive
        var sawNoCapture = !replay.snapshot().captureActive
        while (replay.snapshot().state != SurveyReplayState.COMPLETED) {
            val snapshot = replay.advance()
            sawCapture = sawCapture || snapshot.captureActive
            sawNoCapture = sawNoCapture || !snapshot.captureActive
        }

        assertTrue(sawCapture)
        assertTrue(sawNoCapture)
    }

    @Test
    fun `preview never changes mission contents`() {
        val mission = mission()
        val original = SurveyMissionJson.encode(mission)
        val replay = SurveyMissionReplay(mission)
        replay.start()
        repeat(10) { replay.advance() }
        replay.pause()
        replay.stop()

        assertEquals(original, SurveyMissionJson.encode(mission))
        assertFalse(replay.snapshot().captureActive && replay.snapshot().point == null)
    }
}
