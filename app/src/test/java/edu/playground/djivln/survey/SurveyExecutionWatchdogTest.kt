package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyExecutionWatchdogTest {
    private val allowed = SurveyExecutionGateResult(true, emptySet(), 4.0)

    @Test
    fun `healthy running state continues`() {
        val decision = SurveyExecutionWatchdog.inspect(
            SurveyExecutionState.RUNNING, allowed, true, 1_000L, 2_000L,
        )

        assertEquals(SurveyFailsafeAction.CONTINUE, decision.action)
    }

    @Test
    fun `stale flight telemetry requests resumable pause`() {
        assertRecoverablePause(SurveyExecutionBlock.TELEMETRY_STALE)
    }

    @Test
    fun `simulator exit requests resumable pause`() {
        assertRecoverablePause(SurveyExecutionBlock.SIMULATOR_REQUIRED)
    }

    @Test
    fun `manual takeover requests zero and release`() {
        assertInterventionPause(SurveyExecutionBlock.MANUAL_TAKEOVER)
    }

    @Test
    fun `DJI return home requests resumable pause`() {
        assertInterventionPause(SurveyExecutionBlock.FLIGHT_CONTROLLER_FAILSAFE_ACTIVE)
    }

    @Test
    fun `externally released virtual stick requests resumable pause`() {
        assertInterventionPause(SurveyExecutionBlock.VIRTUAL_STICK_REQUIRED)
    }

    @Test
    fun `waypoint timeout requests resumable pause`() {
        val decision = SurveyExecutionWatchdog.inspect(
            SurveyExecutionState.RUNNING, allowed, true, 2_000L, 2_000L,
        )

        assertEquals(SurveyFailsafeAction.PAUSE_ZERO_AND_RELEASE, decision.action)
        assertTrue(decision.reason!!.contains("timeout"))
    }

    @Test
    fun `debug snapshot can never drive a running controller`() {
        val decision = SurveyExecutionWatchdog.inspect(
            SurveyExecutionState.RUNNING, allowed, false, 1_000L, 2_000L,
        )

        assertEquals(SurveyFailsafeAction.ABORT_ZERO_AND_RELEASE, decision.action)
        assertTrue(decision.reason!!.contains("untrusted"))
    }

    private fun assertRecoverablePause(block: SurveyExecutionBlock) {
        val decision = SurveyExecutionWatchdog.inspect(
            SurveyExecutionState.RUNNING,
            SurveyExecutionGateResult(false, setOf(block), 4.0),
            true,
            1_000L,
            2_000L,
        )

        assertEquals(SurveyFailsafeAction.PAUSE_ZERO_AND_RELEASE, decision.action)
        assertTrue(decision.reason!!.contains(block.name))
    }

    private fun assertInterventionPause(block: SurveyExecutionBlock) {
        val decision = SurveyExecutionWatchdog.inspect(
            SurveyExecutionState.RUNNING,
            SurveyExecutionGateResult(false, setOf(block), 4.0),
            true,
            1_000L,
            2_000L,
        )

        assertEquals(SurveyFailsafeAction.PAUSE_ZERO_AND_RELEASE, decision.action)
        assertTrue(decision.reason!!.contains(block.name))
    }
}
