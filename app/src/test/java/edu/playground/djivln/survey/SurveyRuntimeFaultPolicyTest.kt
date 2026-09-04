package edu.playground.djivln.survey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyRuntimeFaultPolicyTest {
    @Test
    fun `recognizes DJI process timeout`() {
        assertTrue(SurveyRuntimeFaultPolicy.isTimeout("Execution of this process has timed out"))
    }

    @Test
    fun `recognizes localized timeout`() {
        assertTrue(SurveyRuntimeFaultPolicy.isTimeout("相机指令超时"))
    }

    @Test
    fun `undefined camera error is not classified as timeout`() {
        assertFalse(SurveyRuntimeFaultPolicy.isTimeout("Undefined Error"))
    }

    @Test
    fun `only survey camera timeout pauses from generic action callback`() {
        assertTrue(SurveyRuntimeFaultPolicy.shouldPauseCameraAction(
            SurveyRuntimeFaultPolicy.ACTION_SURVEY_CAMERA, false, "Execution of this process has timed out",
        ))
        assertFalse(SurveyRuntimeFaultPolicy.shouldPauseCameraAction(
            "photo", false, "Execution of this process has timed out",
        ))
        assertFalse(SurveyRuntimeFaultPolicy.shouldPauseCameraAction(
            SurveyRuntimeFaultPolicy.ACTION_SURVEY_CAMERA, true, "single-photo ready",
        ))
    }
}
