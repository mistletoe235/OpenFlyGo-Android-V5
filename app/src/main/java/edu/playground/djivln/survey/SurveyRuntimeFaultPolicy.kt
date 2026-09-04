package edu.playground.djivln.survey

object SurveyRuntimeFaultPolicy {
    const val ACTION_SURVEY_CAMERA = "survey_camera"
    @JvmStatic
    fun isTimeout(message: String?): Boolean {
        val normalized = message?.trim()?.lowercase().orEmpty()
        return normalized.contains("timeout") ||
            normalized.contains("timed out") ||
            // Recognize legacy Chinese SDK/app errors after a runtime locale switch.
            normalized.contains("\u8d85\u65f6")
    }

    @JvmStatic
    fun shouldPauseCameraAction(label: String?, ok: Boolean, message: String?): Boolean {
        if (ok || label != ACTION_SURVEY_CAMERA) return false
        return isTimeout(message)
    }
}
