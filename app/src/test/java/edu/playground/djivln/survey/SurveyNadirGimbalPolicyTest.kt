package edu.playground.djivln.survey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyNadirGimbalPolicyTest {
    @Test
    fun `nadir at pitch stop is a mechanical limit`() {
        assertTrue(SurveyNadirGimbalPolicy.isMechanicalLimit(-90.0, false, true))
        assertFalse(SurveyNadirGimbalPolicy.isMechanicalLimit(-45.0, false, true))
        assertFalse(SurveyNadirGimbalPolicy.isMechanicalLimit(-90.0, true, true))
    }

    @Test
    fun `limited pass start moves without enabling capture`() {
        assertTrue(SurveyNadirGimbalPolicy.shouldDeferCaptureStart(
            CaptureAction.START_DISTANCE_INTERVAL,
            mechanicalLimit = true,
        ))
        assertFalse(SurveyNadirGimbalPolicy.canCapture(settled = false))
        assertTrue(SurveyNadirGimbalPolicy.canCapture(settled = true))
    }

    @Test
    fun `unsettled pass end skips the final frame`() {
        assertTrue(SurveyNadirGimbalPolicy.shouldSkipEndFrame(
            CaptureAction.STOP_DISTANCE_INTERVAL,
            settled = false,
        ))
        assertFalse(SurveyNadirGimbalPolicy.shouldSkipEndFrame(
            CaptureAction.STOP_DISTANCE_INTERVAL,
            settled = true,
        ))
    }
}
