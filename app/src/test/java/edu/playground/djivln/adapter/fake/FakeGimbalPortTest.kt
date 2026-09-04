package edu.playground.djivln.adapter.fake

import edu.playground.djivln.survey.SurveyGimbalSettlePolicy
import edu.playground.djivln.survey.SurveyNadirGimbalPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeGimbalPortTest {
    @Test
    fun constrainedNadirIsReportedAsMechanicalLimitNotSettled() {
        val port = FakeGimbalPort(minimumPitchDegrees = -75.0)
        port.rotateToPitch(-90.0) { assertTrue(it.isSuccess) }

        val state = port.state()
        val settled = SurveyGimbalSettlePolicy.isSettled(-90.0, state.pitchDegrees!!)
        assertFalse(settled)
        assertTrue(state.pitchLimited)
        assertTrue(SurveyNadirGimbalPolicy.isMechanicalLimit(-90.0, settled, state.pitchLimited))
    }

    @Test
    fun fortyFiveDegreeCommandSettlesNormally() {
        val port = FakeGimbalPort()
        port.rotateToPitch(-45.0) { }
        assertTrue(SurveyGimbalSettlePolicy.isSettled(-45.0, port.state().pitchDegrees!!))
        assertFalse(port.state().pitchLimited)
    }
}
