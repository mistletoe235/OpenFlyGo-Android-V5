package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurveyCaptureFeedbackTest {
    @Test
    fun virtualStickCaptureSuccessShowsCaptured() {
        assertEquals(true, SurveyCaptureFeedback.result("capture_result", mapOf(
            "backend" to "CUSTOM_VIRTUAL_STICK", "success" to true,
        )))
    }

    @Test
    fun virtualStickFailureShowsFailureNotCaptured() {
        assertEquals(false, SurveyCaptureFeedback.result("capture_result", mapOf(
            "backend" to "CUSTOM_VIRTUAL_STICK", "success" to false, "error" to "timeout",
        )))
    }

    @Test
    fun kmzSuccessAndFailureUseTheSameFeedback() {
        listOf(true, false).forEach { success ->
            assertEquals(success, SurveyCaptureFeedback.result("dji_app_capture_result", mapOf("success" to success)))
        }
    }

    @Test
    fun hilCompletedCaptureAlsoShowsItsActualResult() {
        listOf(true, false).forEach { success ->
            assertEquals(success, SurveyCaptureFeedback.result("capture_result", mapOf(
                "backend" to "UE_HIL", "success" to success,
            )))
        }
    }

    @Test
    fun requestTriggerCameraEdgesAndFrameSavingDoNotDuplicateResultFeedback() {
        listOf("dji_app_capture_requested", "camera_photo_captured", "camera_capture_state",
            "capture_file", "trigger_frame_saved", "dji_capture_gimbal_command_result").forEach { event ->
            assertNull(event, SurveyCaptureFeedback.result(event, mapOf("success" to true)))
        }
    }

    @Test
    fun missingOrMalformedResultNeverClaimsSuccess() {
        listOf("capture_result", "dji_app_capture_result").forEach { event ->
            listOf(null, "true", "false", 1).forEach { success ->
                assertNull(SurveyCaptureFeedback.result(event, mapOf("success" to success)))
            }
            assertNull(SurveyCaptureFeedback.result(event, emptyMap()))
        }
    }

    @Test
    fun eachCompletedPhotoProducesOneResultDespiteOtherCaptureEvents() {
        val events = listOf(
            "dji_app_capture_requested" to mapOf("success" to true),
            "camera_photo_captured" to mapOf("success" to true),
            "capture_result" to mapOf("success" to true),
            "camera_capture_state" to mapOf("storing" to true),
            "capture_file" to mapOf("success" to true),
        )
        assertEquals(listOf(true), events.mapNotNull { (event, fields) -> SurveyCaptureFeedback.result(event, fields) })
    }
}
