package edu.playground.djivln.survey

object SurveyCaptureFeedback {
    fun result(event: String, fields: Map<String, Any?>): Boolean? = when (event) {
        "capture_result", "dji_app_capture_result" -> fields["success"] as? Boolean
        else -> null
    }
}
